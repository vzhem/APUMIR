#!/usr/bin/env python3
"""Exercise permission-runner safety and failure branches without adb or Gradle."""
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / 'scripts/ci'
spec = importlib.util.spec_from_file_location('notification_results', CI / 'check-android-notification-results.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class PermissionRunnerTest(unittest.TestCase):
    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory(prefix='notification-runner-') as tmp:
            root = Path(tmp)
            ci = root / 'scripts/ci'
            app = root / 'android-app'
            bin_dir = root / 'bin'
            ci.mkdir(parents=True)
            app.mkdir()
            bin_dir.mkdir()
            for name in ('run-android-navigation-tests.sh', 'check-android-notification-results.py',
                         'check-android-instrumentation-phase.py'):
                shutil.copy(CI / name, ci)
            for name in ('check-outbox-fairness.py', 'check-outbox-recipient-sql.py',
                         'check-direct-reply-scope.py', 'check-push-mailbox-sql.py',
                         'check-local-notification-sql.py', 'check-android-navigation-results.py'):
                (ci / name).write_text('raise SystemExit(0)\n', encoding='utf-8')
            main = {name: sorted(methods) for name, methods in checker.EXPECTED.items()
                    if name != checker.PERMISSION_CLASS}
            (root / 'cases.json').write_text(json.dumps(main), encoding='utf-8')
            (app / 'gradlew').write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import json, os, sys, xml.etree.ElementTree as ET
                from pathlib import Path
                scenario = os.environ['SCENARIO']
                with open(os.environ['TRACE'], 'a') as trace:
                    trace.write('gradle ' + ' '.join(sys.argv[1:]) + '\\n')
                if ':app:installDebug' in sys.argv and scenario == 'install-fails':
                    raise SystemExit(1)
                report = Path('app/build/outputs/androidTest-results/connected')
                report.mkdir(parents=True, exist_ok=True)
                suite = ET.Element('testsuite')
                selection = next((s.split('=', 1)[1] for s in sys.argv if 'RunnerArguments.class=' in s), '')
                if selection:
                    for classname, methods in json.loads(Path(os.environ['CASES']).read_text()).items():
                        for method in methods:
                            ET.SubElement(suite, 'testcase', classname=classname, name=method)
                ET.ElementTree(suite).write(report / 'results.xml')
            '''), encoding='utf-8')
            (app / 'gradlew').chmod(0o755)
            (bin_dir / 'adb').write_text(textwrap.dedent('''\
                #!/usr/bin/env python3
                import os, sys
                from pathlib import Path
                command = ' '.join(sys.argv[1:])
                with open(os.environ['TRACE'], 'a') as trace:
                    trace.write('adb ' + command + '\\n')
                scenario = os.environ['SCENARIO']
                marker = Path(os.environ['MARKER'])
                if command == 'get-serialno':
                    print('physical-phone' if scenario == 'physical-device' else 'emulator-5554')
                elif command == 'shell getprop ro.kernel.qemu':
                    print('0' if scenario == 'not-qemu' else '1')
                elif command == 'shell pm list packages com.vladimir.messenger':
                    if scenario == 'existing-package': print('package:com.vladimir.messenger')
                elif scenario == 'isolation-fails' and command == 'shell am force-stop com.vladimir.messenger' and not marker.exists():
                    marker.touch()
                    raise SystemExit(1)
                elif command.startswith('shell run-as com.vladimir.messenger cat '):
                    print('invalid' if scenario == 'invalid-marker' else 'denied' if scenario.startswith('initial-denied') else 'granted')
                elif scenario == 'revoke-fails' and command.startswith('shell pm revoke '):
                    raise SystemExit(1)
                elif scenario == 'grant-fails-once' and command.startswith('shell pm grant ') and not marker.exists():
                    marker.touch()
                    raise SystemExit(1)
                elif scenario == 'restore-fails-once' and command.startswith('shell pm grant '):
                    count = int(marker.read_text()) if marker.exists() else 0
                    marker.write_text(str(count + 1))
                    if count == 1: raise SystemExit(1)  # fail the restore, not the recovery grant
                elif command.startswith('shell am instrument '):
                    selected = sys.argv[sys.argv.index('class') + 1]
                    classname, method = selected.split('#', 1)
                    actual_method = 'unexpected' if scenario == 'guard-unexpected' and method.startswith('disposableProfile') else method
                    end = -3 if scenario == 'guard-skipped' and method.startswith('disposableProfile') else 0
                    if scenario == 'blocked-fails' and method.startswith('revokedPermission'): end = -2
                    if scenario == 'precondition-fails' and method.startswith('permissionIsGranted'): end = -2
                    for code in (1, end):
                        print('INSTRUMENTATION_STATUS: class=' + classname)
                        print('INSTRUMENTATION_STATUS: id=AndroidJUnitRunner')
                        print('INSTRUMENTATION_STATUS: numtests=1')
                        print('INSTRUMENTATION_STATUS: current=1')
                        print('INSTRUMENTATION_STATUS: test=' + actual_method)
                        print('INSTRUMENTATION_STATUS_CODE: ' + str(code))
                    print('INSTRUMENTATION_RESULT: stream=')
                    print('OK (1 test)' if end == 0 else 'FAILURES!!!')
                    print('INSTRUMENTATION_CODE: -1')
                elif command == 'shell settings get system font_scale':
                    print('1.0')
            '''), encoding='utf-8')
            (bin_dir / 'adb').chmod(0o755)
            trace = root / 'trace'
            env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ.get('PATH', ''),
                       SCENARIO=scenario, TRACE=str(trace), CASES=str(root / 'cases.json'),
                       MARKER=str(root / 'marker'))
            result = subprocess.run(['bash', str(ci / 'run-android-navigation-tests.sh')], cwd=root,
                                    env=env, text=True, capture_output=True, timeout=30)
            return result, trace.read_text(encoding='utf-8').splitlines()

    @staticmethod
    def matching(trace, prefix):
        return [line for line in trace if line.startswith(prefix)]

    def test_granted_original_runs_without_reinstalling_between_phases_and_restores(self):
        result, trace = self.run_scenario('success')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        guard = next(i for i, x in enumerate(trace) if '#disposableProfileSnapshotsTheOriginalPermission' in x)
        pre = next(i for i, x in enumerate(trace) if '#permissionIsGrantedBeforeRevocation' in x)
        revoke = next(i for i, x in enumerate(trace) if x.startswith('adb shell pm revoke '))
        denied = next(i for i, x in enumerate(trace) if '#revokedPermissionLeavesTheCommittedNotificationPending' in x)
        grant = next(i for i, x in enumerate(trace) if x.startswith('adb shell pm grant '))
        posted = next(i for i, x in enumerate(trace) if '#grantingPermissionLetsTheReopenedPendingRowPostOnce' in x)
        self.assertLess(guard, pre)
        self.assertLess(pre, revoke)
        self.assertLess(revoke, denied)
        self.assertLess(denied, grant)
        self.assertLess(grant, posted)
        self.assertEqual(2, len(self.matching(trace, 'adb shell pm grant '))) # recovery and restore
        self.assertEqual(1, len([x for x in trace if x.startswith('gradle ') and ':app:installDebug' in x]))
        self.assertFalse(any(x.startswith('gradle ') for x in trace[guard:posted + 1]))
        self.assertTrue(any(x.startswith('adb shell run-as com.vladimir.messenger rm -f ') for x in trace))

    def test_initially_denied_state_is_restored_after_the_real_transition(self):
        result, trace = self.run_scenario('initial-denied')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        pre = next(i for i, x in enumerate(trace) if '#permissionIsGrantedBeforeRevocation' in x)
        grants = [i for i, x in enumerate(trace) if x.startswith('adb shell pm grant ')]
        revokes = [i for i, x in enumerate(trace) if x.startswith('adb shell pm revoke ')]
        final = next(i for i, x in enumerate(trace) if '#grantingPermissionLetsTheReopenedPendingRowPostOnce' in x)
        self.assertEqual(2, len(grants))
        self.assertEqual(2, len(revokes))
        self.assertLess(grants[0], pre)
        self.assertLess(pre, revokes[0])
        self.assertLess(revokes[0], grants[1])
        self.assertLess(final, revokes[1])

    def test_untrusted_device_or_failed_guard_never_changes_permission(self):
        for scenario in ('physical-device', 'not-qemu', 'existing-package', 'guard-skipped',
                         'guard-unexpected', 'isolation-fails', 'install-fails', 'invalid-marker', 'precondition-fails'):
            with self.subTest(scenario=scenario):
                result, trace = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse(self.matching(trace, 'adb shell pm revoke '))
                self.assertFalse(self.matching(trace, 'adb shell pm grant '))
                self.assertFalse(any('#revokedPermission' in x for x in trace))
        for scenario in ('physical-device', 'not-qemu', 'existing-package'):
            _, trace = self.run_scenario(scenario)
            self.assertFalse(self.matching(trace, 'gradle '))

    def test_revoke_or_denied_phase_failure_still_restores_original_state(self):
        for scenario in ('revoke-fails', 'blocked-fails'):
            with self.subTest(scenario=scenario):
                result, trace = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual(1, len(self.matching(trace, 'adb shell pm revoke ')))
                self.assertEqual(1, len(self.matching(trace, 'adb shell pm grant ')))
                self.assertFalse(any('#grantingPermission' in x for x in trace))

    def test_failed_recovery_or_restore_grant_is_retried_and_job_still_fails(self):
        for scenario, grants in (('grant-fails-once', 2), ('restore-fails-once', 3)):
            with self.subTest(scenario=scenario):
                result, trace = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual(grants, len(self.matching(trace, 'adb shell pm grant ')))
        _, trace = self.run_scenario('grant-fails-once')
        self.assertFalse(any('#grantingPermission' in x for x in trace))


if __name__ == '__main__':
    unittest.main(verbosity=2)

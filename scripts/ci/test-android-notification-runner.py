#!/usr/bin/env python3
"""Exercise the permission-test runner's safety and failure branches without adb or Gradle."""
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
            shutil.copy(CI / 'run-android-navigation-tests.sh', ci)
            shutil.copy(CI / 'check-android-notification-results.py', ci)
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
                report = Path('app/build/outputs/androidTest-results/connected')
                report.mkdir(parents=True, exist_ok=True)
                suite = ET.Element('testsuite')
                selection = next((s.split('=', 1)[1] for s in sys.argv if 'RunnerArguments.class=' in s), '')
                if '#' in selection:
                    classname, method = selection.split('#', 1)
                    case = ET.SubElement(suite, 'testcase', classname=classname, name=method)
                    if scenario == 'guard-skipped' and method.startswith('disposableProfile'):
                        ET.SubElement(case, 'skipped')
                    if scenario == 'guard-unexpected' and method.startswith('disposableProfile'):
                        ET.SubElement(suite, 'testcase', classname='unexpected.Test', name='notSelected')
                    if scenario == 'blocked-fails' and method.startswith('revokedPermission'):
                        ET.SubElement(case, 'failure')
                elif selection:
                    for classname, methods in json.loads(Path(os.environ['CASES']).read_text()).items():
                        for method in methods:
                            ET.SubElement(suite, 'testcase', classname=classname, name=method)
                ET.ElementTree(suite).write(report / 'results.xml')
                if scenario == 'blocked-fails' and selection.endswith('#revokedPermissionLeavesTheCommittedNotificationPending'):
                    raise SystemExit(1)
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
                if scenario == 'isolation-fails' and command == 'shell am force-stop com.vladimir.messenger' and not marker.exists():
                    marker.touch()
                    raise SystemExit(1)
                if scenario == 'revoke-fails' and command.startswith('shell pm revoke '):
                    raise SystemExit(1)
                if scenario == 'grant-fails-once' and command.startswith('shell pm grant ') and not marker.exists():
                    marker.touch()
                    raise SystemExit(1)
                if command == 'shell settings get system font_scale':
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

    def test_success_runs_the_guard_denied_and_granted_phases_in_order(self):
        result, trace = self.run_scenario('success')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        guard = next(i for i, x in enumerate(trace) if '#disposableProfileStartsWithPermissionGranted' in x)
        revoke = next(i for i, x in enumerate(trace) if x.startswith('adb shell pm revoke '))
        denied = next(i for i, x in enumerate(trace) if '#revokedPermissionLeavesTheCommittedNotificationPending' in x)
        grant = next(i for i, x in enumerate(trace) if x.startswith('adb shell pm grant '))
        restored = next(i for i, x in enumerate(trace) if '#grantingPermissionLetsTheReopenedPendingRowPostOnce' in x)
        self.assertLess(guard, revoke)
        self.assertLess(revoke, denied)
        self.assertLess(denied, grant)
        self.assertLess(grant, restored)
        self.assertEqual(1, len(self.matching(trace, 'adb shell pm grant ')))

    def test_skipped_or_wrong_guard_never_changes_permission(self):
        for scenario in ('guard-skipped', 'guard-unexpected', 'isolation-fails'):
            with self.subTest(scenario=scenario):
                result, trace = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertFalse(self.matching(trace, 'adb shell pm revoke '))
                self.assertFalse(self.matching(trace, 'adb shell pm grant '))
                self.assertFalse(any('#revokedPermission' in x for x in trace))

    def test_failure_during_revoke_or_denied_phase_still_restores_permission(self):
        for scenario in ('revoke-fails', 'blocked-fails'):
            with self.subTest(scenario=scenario):
                result, trace = self.run_scenario(scenario)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertEqual(1, len(self.matching(trace, 'adb shell pm revoke ')))
                self.assertEqual(1, len(self.matching(trace, 'adb shell pm grant ')))

    def test_failed_grant_is_retried_by_exit_trap_and_job_still_fails(self):
        result, trace = self.run_scenario('grant-fails-once')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(2, len(self.matching(trace, 'adb shell pm grant ')))
        self.assertFalse(any('#grantingPermission' in x for x in trace))


if __name__ == '__main__':
    unittest.main(verbosity=2)

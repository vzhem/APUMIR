#!/usr/bin/env python3
"""Safety wiring contracts; actual SQLite/Rust and JVM tests run separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"

def source(path):
    return (ROOT / path).read_text()

class AutomaticMaintenanceContracts(unittest.TestCase):
    def test_native_maintenance_is_independent_and_off_the_async_executor(self):
        core = source("rust-core/src/engine/core.rs")
        section = core[core.index("// Independent of MQTT"):core.index("let (mqtt_outbound_tx", core.index("// Independent of MQTT"))]
        self.assertIn("spawn_blocking", section)
        self.assertIn("maintenance_for_epoch", section)
        self.assertIn("queue.cleanup_expired()", section)
        self.assertNotIn("is_connected", section)

    def test_sql_maintenance_has_replay_age_disk_budget_and_interrupt_guards(self):
        store = source("rust-core/src/storage/relay_store.rs")
        section = store[store.index("pub fn automatic_maintenance"):store.index("/// Явно заменить/добавить зашифрованную запись")]
        for token in ("try_lock()", "progress_handler", "DEFAULT_RELAY_TTL", "saturating_add(24", "LIMIT 5000", "available_space", "last_compact_ms", "VACUUM", "incremental_vacuum", "wal_checkpoint(TRUNCATE)", "None::<fn() -> bool>"):
            self.assertIn(token, section)
        self.assertNotIn("remove_file", section)
        self.assertNotIn("decrypt_record", section)
        self.assertIn("interrupt_for_shutdown()", source("rust-core/src/engine/core.rs"))

    def test_repeated_cleanup_and_markers_do_not_create_or_refresh_garbage(self):
        store = source("rust-core/src/storage/relay_store.rs")
        section = store[store.index("pub fn remove_encrypted_and_tombstone"):store.index("pub fn purge_expired_encrypted")]
        self.assertIn("if removed > 0", section)
        self.assertIn("INSERT OR IGNORE INTO relay_tombstones", store)
        prune = store[store.index("const SQL_TOMBSTONE_PRUNE"):store.index("// ═", store.index("const SQL_TOMBSTONE_PRUNE"))]
        self.assertIn("removed_at_ms <= ?1", prune)
        self.assertNotIn("NOT IN", prune)

    def test_foreign_admission_is_bounded_but_own_store_is_protected(self):
        store = source("rust-core/src/storage/relay_store.rs")
        foreign = store[store.index("pub fn store_foreign_encrypted"):store.index("pub fn automatic_maintenance")]
        self.assertIn("20_000", foreign)
        self.assertIn("256 * 1024 * 1024", foreign)
        self.assertNotIn("DELETE", foreign)
        self.assertIn("if is_own_origin", source("rust-core/src/engine/core.rs"))

    def test_ephemeral_hint_never_weakens_sealing_or_media_reference_semantics(self):
        bridge = (APP / "data/RustBridge.kt").read_text()
        control = (APP / "data/security/EphemeralControl.kt").read_text()
        self.assertLess(bridge.index("sealOutgoing(recipientId, text) ?: return false", bridge.index("fun sendMessage(")), bridge.index("EphemeralControl.transportId"))
        self.assertNotIn('"ref"', control)
        self.assertIn("ServiceSignal", source("rust-core/src/network/relay_queue.rs"))

    def test_no_blanket_user_storage_clear(self):
        maintenance = (APP / "data/maintenance/AutomaticMaintenance.kt").read_text()
        for forbidden in ('File(app.cacheDir, "voice")', '"file_received/v1"', "deleteRecursively", "purgeCompletedTransfers", "cancelStalledOutgoing"):
            self.assertNotIn(forbidden, maintenance)
        self.assertIn("OneTimeWorkRequestBuilder", maintenance)
        self.assertIn("PeriodicWorkRequestBuilder", maintenance)
        self.assertIn("AutomaticMaintenance.schedule", (APP / "MessengerApplication.kt").read_text())
        sweep = (APP / "data/maintenance/TemporaryFileSweep.kt").read_text()
        for token in ("isSymbolicLink", "NOFOLLOW_LINKS", "deleted < 128", "examined < 4096", "modified > nowMs"):
            self.assertIn(token, sweep)

    def test_file_cleanup_is_fail_closed_and_coordinates_with_receiver_sender(self):
        router = (APP / "data/file/FileTransferRouter.kt").read_text()
        section = router[router.index("suspend fun purgeExpiredTransfers("):router.index("suspend fun cancelStalledOutgoing")]
        self.assertIn("outgoingTransferMutex.tryLock()", section)
        self.assertIn("receiver.tryMaintenance", section)
        self.assertIn("catch (_: Exception) { continue }", section)
        self.assertIn("isOldOrphan", section)
        self.assertNotIn("getTransfer(transferId) }.getOrNull()", section)
        worker = (APP / "worker/MaintenanceWorker.kt").read_text()
        self.assertIn("purgeExpiredTransfers()", worker)
        self.assertNotIn("pumpOutgoing", worker)

    def test_download_cleanup_checks_ownership_and_installation_grace(self):
        cleanup = (APP / "data/update/DownloadTrashCleanup.kt").read_text()
        self.assertEqual(cleanup.count("OWNER_PACKAGE_NAME"), 2)
        self.assertIn("24L * 60 * 60 * 1000", cleanup)
        self.assertIn("ids.size < 128", cleanup)

    def test_compaction_reacts_to_a_grown_file_and_never_fakes_success(self):
        """2026-10-15: файл 1.15 ГБ, 954 МБ переиспользуемых, уплотнений ноль.

        Разбор отчёта второго телефона: порог свободного места считался по
        всему файлу (`db_bytes * 2 + 64 МиБ`), поэтому на телефоне, где файл
        вырос вокруг переиспользуемых страниц, он не выполнялся НИКОГДА; а
        разовый VACUUM всё равно обрывался об общий пятисекундный бюджет.
        """
        store = source("rust-core/src/storage/relay_store.rs")
        start = store.index("let mut compacted = false;")
        section = store[start:store.index("wal_checkpoint(TRUNCATE)", start)]
        self.assertIn("live_bytes", section)
        # Порог «вдвое больше файла плюс запас» снят: он считался по всему
        # файлу, а не по живым записям (в комментарии он остаётся как история).
        self.assertNotIn("let db_bytes", section)
        self.assertNotIn("saturating_mul(2)", section)
        # Разовое преобразование живёт на своём бюджете, и сторож смены
        # поколения остаётся: остановка ядра отменяет работу сразу.
        self.assertIn("COMPACTION_BUDGET_SECS", store)
        self.assertLess(section.index("progress_handler"), section.index("VACUUM"))
        # Суточное окно закрывается только за то, что действительно вышло.
        self.assertLess(section.index("VACUUM"), section.index("UPDATE relay_maintenance SET last_compact_ms"))
        # Отказ по месту обязан быть виден в логе: молчание этого гейта и
        # стоило разбора вслепую.
        self.assertIn("MESH compaction: deferred", section)

if __name__ == "__main__":
    unittest.main(verbosity=2)

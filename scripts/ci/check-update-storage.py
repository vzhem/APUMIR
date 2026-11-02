#!/usr/bin/env python3
"""Контракт безопасного места обновлений: лишние копии APK видны и убираются,
но переписка, ключи, голосовые и документы пользователя не затрагиваются."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android-app/app/src/main/java/com/vladimir/messenger"
RES = ROOT / "android-app/app/src/main/res"


def read(path):
    return (MAIN / path).read_text(encoding="utf-8")


class UpdateStorageContract(unittest.TestCase):
    def test_self_seed_does_not_keep_a_second_plaintext_apk(self):
        seeder = read("data/update/ApkSeeder.kt")
        self.assertIn("copyPlaintext: Boolean = true", seeder)
        self.assertIn('autoReseed = true, copyPlaintext = false)', seeder)
        self.assertIn("source = if (copyPlaintext || selfSource) copy else source", seeder)
        self.assertIn("copyPlaintext = comparison > 0", seeder)

    def test_cleanup_is_scoped_to_redundant_update_copies_and_keeps_the_active_seed(self):
        maintenance = read("data/update/UpdateStorageMaintenance.kt")
        policy = read("data/update/UpdateStoragePolicy.kt")
        downloads = read("data/update/ApkDownloadMaintenance.kt")
        chunks = read("data/file/FileTransferChunkStore.kt")
        self.assertIn("ApkUpdate.CHAT_ID", policy)
        self.assertIn('direction != "INCOMING" || state != "COMPLETE"', policy)
        self.assertIn("mayDeleteRedundantEncryptedTransfer", policy)
        self.assertIn("mayDeleteInstalledDownload", policy)
        self.assertIn("protectedSeedTransferId", maintenance)
        self.assertIn("installedSeedReady", maintenance)
        self.assertIn("receivedStore.deleteTransfer(row.transferId)", maintenance)
        self.assertIn("transferDao.deleteTransfer(transferId)", maintenance)
        self.assertIn("FileTransferKeyVault.deleteKey(app, transferId)", maintenance)
        self.assertIn("chunkStore.deleteTransfer(transferId)", maintenance)
        self.assertIn("router.groupSeeder.forget(transferId)", maintenance)
        self.assertIn("cleanupInstalledDownloads", maintenance)
        self.assertIn("manager.remove(download.downloadId)", downloads)
        self.assertIn("transferChunkBytes", chunks)
        self.assertNotIn("purgeCompletedTransfers", maintenance)
        self.assertNotIn("deleteRecursively", maintenance)
        self.assertIn("isOrphanSeedArtifact", maintenance)
        self.assertIn("GRACE_MS", maintenance)

    def test_worker_and_settings_use_the_same_cleanup(self):
        worker = read("worker/MaintenanceWorker.kt")
        vm = read("ui/screens/settings/SettingsViewModel.kt")
        cards = read("ui/screens/settings/FileTransferSettingsCards.kt")
        self.assertIn("updateStorageMaintenance().cleanup()", worker)
        self.assertIn("updateStorage.cleanup()", vm)
        self.assertIn("updateStorage.report()", vm)
        self.assertIn("FileTransferSettingsCards(viewModel)", read("ui/screens/settings/SettingsScreen.kt"))
        self.assertIn("viewModel.refreshUpdateStorage()", cards)
        self.assertIn("viewModel::onCleanUpdateStorage", cards)

    def test_installer_handoff_keeps_pending_newer_and_cleans_installed(self):
        handoff = read("data/update/UpdateInstallHandoff.kt")
        self.assertIn("comparison <= 0 || age >= ApkUpdate.TRANSFER_TTL_MS", handoff)
        self.assertIn("Files.isSymbolicLink", handoff)
        self.assertIn("files.take(256)", handoff)
        seeder = read("data/update/ApkSeeder.kt")
        self.assertIn("UpdateInstallHandoff.copy(appContext, file, readyInfo.sha256, readyInfo.version)", seeder)

    def test_storage_strings_exist_in_both_languages(self):
        names = [
            "storage_update_title", "storage_update_hint", "storage_update_installed",
            "storage_update_copies", "storage_update_note", "storage_update_clean",
            "st_update_storage_cleaned",
        ]
        for locale in ("values", "values-en"):
            xml = (RES / locale / "strings.xml").read_text(encoding="utf-8")
            for name in names:
                self.assertEqual(1, xml.count(f'name="{name}"'), f"{locale}: {name}")

    def test_policy_has_jvm_tests(self):
        test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/update/UpdateStoragePolicyTest.kt").read_text()
        self.assertIn("receivedPlaintextIsRemovedOnlyForInstalledUpdateInVirtualSeedChat", test)
        self.assertIn("encryptedCleanupKeepsCurrentSeedAndPendingNewerUpdates", test)
        self.assertIn("installedPublicDownloadCanBeRemovedButPendingAndUnrelatedDownloadsAreKept", test)
        self.assertIn("orphanRuleProtectsActiveArtifactsAndMetadata", test)
        chunk_test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/file/FileTransferChunkStoreTest.kt").read_text()
        self.assertIn("transferChunkByteCountIsLimitedToOneTransferAndIgnoresMetadata", chunk_test)
        handoff_test = (ROOT / "android-app/app/src/test/java/com/vladimir/messenger/data/update/UpdateInstallHandoffTest.kt").read_text()
        self.assertIn("installedHandoffIsRemovedButPendingNewerIsKept", handoff_test)


if __name__ == "__main__":
    unittest.main(verbosity=2)

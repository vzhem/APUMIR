#!/usr/bin/env python3
"""Synthetic SQLite characterization using SQL extracted from RelayStore.

No phone DB, identity, body decryption or real cleanup. SQL tests are not a
Rust/Android integration test; real encrypted-store/queue tests run separately.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "rust-core/src/storage/relay_store.rs").read_text()


def sql(name):
    pattern = r'(?:pub\s+)?const\s+' + re.escape(name) + r':\s*&str\s*=\s*"([\s\S]*?)";'
    result = re.search(pattern, SOURCE)
    if not result:
        raise AssertionError(f"missing actual SQL constant: {name}")
    return result.group(1)


def schema(connection):
    connection.executescript(sql("MIGRATION_RELAY_V1"))
    connection.executescript(sql("MIGRATION_RELAY_V2"))


def insert(connection, identity, body=b"synthetic-opaque-envelope"):
    connection.execute(sql("SQL_STORE_ENC"), (identity, "synthetic-recipient", 10000, body))


def count(connection, table="relay_records_enc"):
    return connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


class RelayStorageForensics(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        schema(self.db)

    def tearDown(self):
        self.db.close()

    def test_same_transport_id_repeated_does_not_create_rows(self):
        for _ in range(200):
            insert(self.db, "same-id")
        self.assertEqual(1, count(self.db))

    def test_same_body_with_new_ids_creates_separate_rows(self):
        for n in range(200):
            insert(self.db, f"fresh-id-{n}")
        self.assertEqual(200, count(self.db))

    def test_repeated_marker_deduplicates_without_refreshing_its_age(self):
        for n in range(200):
            self.db.execute(sql("SQL_TOMBSTONE_ADD"), ("same-id", n))
        self.assertEqual(1, count(self.db, "relay_tombstones"))
        self.assertEqual(0, self.db.execute("SELECT removed_at_ms FROM relay_tombstones").fetchone()[0])

    def test_unknown_cleanup_does_not_create_a_permanent_marker(self):
        # Mirrors remove_encrypted_and_tombstone's conditional INSERT.
        with self.db:
            removed = self.db.execute(sql("SQL_REMOVE_ENC"), ("unknown-id",)).rowcount
            if removed:
                self.db.execute(sql("SQL_TOMBSTONE_ADD"), ("unknown-id", 100))
        self.assertEqual(0, removed)
        self.assertEqual(0, count(self.db, "relay_tombstones"))

    def test_record_expiry_does_not_itself_prune_markers(self):
        insert(self.db, "expired")
        self.db.execute(sql("SQL_TOMBSTONE_ADD"), ("old-marker", 1))
        self.db.execute(sql("SQL_PURGE_EXPIRED_ENC"), (10001,))
        self.db.execute(sql("SQL_QUARANTINE_PURGE_EXPIRED"), (10001,))
        self.assertEqual(0, count(self.db))
        self.assertEqual(1, count(self.db, "relay_tombstones"))

    def test_deleted_payload_pages_remain_on_disk_until_explicit_compaction(self):
        target = ROOT / "target/relay-forensics"
        target.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=target) as directory:
            file = Path(directory) / "synthetic.sqlite"
            db = sqlite3.connect(file)
            try:
                schema(db)
                self.assertEqual(0, db.execute("PRAGMA auto_vacuum").fetchone()[0])
                for n in range(128):
                    insert(db, f"temporary-{n}", b"x" * 8192)
                insert(db, "kept", b"keep-exactly")
                db.commit()
                before = file.stat().st_size
                db.execute("DELETE FROM relay_records_enc WHERE msg_id <> 'kept'")
                db.commit()
                self.assertEqual(before, file.stat().st_size)
                self.assertGreater(db.execute("PRAGMA freelist_count").fetchone()[0], 0)
                # ONLY this disposable synthetic fixture, never a phone DB.
                db.execute("VACUUM")
                self.assertLess(file.stat().st_size, before)
                self.assertEqual([(b"keep-exactly",)], db.execute("SELECT envelope FROM relay_records_enc").fetchall())
            finally:
                db.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)

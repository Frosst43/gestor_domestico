#!/usr/bin/env python3
"""Execute Kotlin-generated SQL against desktop SQLite, not Android/Room.
Usage: python3 scripts/check_sync_journal.py path/to/emitted-sync-sql.txt
The emitter is tools/EmitSyncSql.kt; do not substitute hand-written SQL.
"""
import base64
import pathlib
import sqlite3
import sys
import tempfile
import unittest

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
SQL = {key: [] for key in ("TABLE", "IDENTITY", "SEED", "TRIGGER")}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    key, encoded = line.split("\t", 1)
    SQL[key].append(base64.b64decode(encoded, validate=True).decode("utf-8"))
sys.argv = [sys.argv[0]]

OLD = """
PRAGMA foreign_keys=ON;
CREATE TABLE shopping_lists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL,
    archived INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, listId TEXT NOT NULL,
    name TEXT NOT NULL, updatedAt INTEGER NOT NULL,
    FOREIGN KEY(listId) REFERENCES shopping_lists(id) ON DELETE CASCADE);
CREATE INDEX index_categories_listId ON categories(listId);
CREATE TABLE products (id TEXT NOT NULL PRIMARY KEY, categoryId TEXT NOT NULL,
    name TEXT NOT NULL, quantity TEXT NOT NULL, notes TEXT NOT NULL,
    purchased INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE);
CREATE INDEX index_products_categoryId ON products(categoryId);
"""

def install(db, seed):
    for key in ("TABLE", "IDENTITY", "SEED" if seed else None, "TRIGGER"):
        for sql in SQL.get(key, []):
            db.execute(sql)


def add_sample(db):
    db.execute("INSERT INTO shopping_lists VALUES ('L','Necesidades casa',0,1,1)")
    db.execute("INSERT INTO categories VALUES ('C','L','Hogar',1)")
    db.execute("INSERT INTO products VALUES ('P','C','Lavadora','1','Medidas',0,1)")


def snapshot(db):
    return [db.execute(f"SELECT * FROM {table} ORDER BY id").fetchall()
            for table in ("shopping_lists", "categories", "products")]


class JournalContract(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript(OLD)
        self.addCleanup(self.db.close)

    def test_01_migration_preserves_rows_and_seeds(self):
        add_sample(self.db)
        before = snapshot(self.db)
        with self.db:
            install(self.db, seed=True)
        self.assertEqual(before, snapshot(self.db))
        self.assertEqual([("LIST", "L", "UPSERT"), ("CATEGORY", "C", "UPSERT"), ("PRODUCT", "P", "UPSERT")],
                         self.db.execute("SELECT entityType,entityId,operation FROM local_change_journal ORDER BY sequence").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_02_writes_and_cascade_deletes_are_recorded(self):
        install(self.db, seed=False)
        add_sample(self.db)
        self.db.execute("UPDATE products SET purchased=1,updatedAt=2 WHERE id='P'")
        self.assertEqual((1,), self.db.execute("SELECT purchased FROM local_change_journal ORDER BY sequence DESC LIMIT 1").fetchone())
        self.db.execute("DELETE FROM shopping_lists WHERE id='L'")
        deleted = self.db.execute("SELECT entityType,entityId,parentId,name FROM local_change_journal WHERE operation='DELETE'").fetchall()
        self.assertEqual({("LIST", "L", None, None), ("CATEGORY", "C", "L", None), ("PRODUCT", "P", "C", None)}, set(deleted))
        self.assertEqual([[], [], []], snapshot(self.db))

    def test_03_rollback_rolls_back_changes_and_journal(self):
        install(self.db, seed=False)
        add_sample(self.db)
        self.db.commit()
        before = snapshot(self.db)
        count = self.db.execute("SELECT COUNT(*) FROM local_change_journal").fetchone()[0]
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("DELETE FROM shopping_lists WHERE id='L'")
                self.db.execute("INSERT INTO products VALUES ('X','MISSING','Fail','','',0,1)")
        self.assertEqual(before, snapshot(self.db))
        self.assertEqual(count, self.db.execute("SELECT COUNT(*) FROM local_change_journal").fetchone()[0])

    def test_04_empty_identity_fails_closed(self):
        install(self.db, seed=False)
        self.db.execute("DELETE FROM local_sync_identity")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO shopping_lists VALUES ('L','No silent loss',0,1,1)")
        self.assertEqual([], self.db.execute("SELECT * FROM shopping_lists").fetchall())

    def test_05_sequence_pages_have_no_duplicates(self):
        install(self.db, seed=False)
        add_sample(self.db)
        self.db.execute("UPDATE products SET name='New name',updatedAt=2 WHERE id='P'")
        high = self.db.execute("SELECT MAX(sequence) FROM local_change_journal").fetchone()[0]
        seen, cursor = [], 0
        while cursor < high:
            page = self.db.execute("SELECT sequence FROM local_change_journal WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT 2", (cursor, high)).fetchall()
            self.assertTrue(page)
            seen.extend(row[0] for row in page)
            cursor = page[-1][0]
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual([1, 2, 3, 4], seen)

    def test_06_identity_journal_and_triggers_survive_reopen(self):
        with tempfile.TemporaryDirectory() as folder:
            file = str(pathlib.Path(folder) / "test.db")
            with sqlite3.connect(file) as db:
                db.executescript(OLD)
                install(db, seed=False)
                add_sample(db)
                identity = db.execute("SELECT deviceId FROM local_sync_identity").fetchone()[0]
            db.close()
            with sqlite3.connect(file) as db:
                db.execute("PRAGMA foreign_keys=ON")
                self.assertEqual(identity, db.execute("SELECT deviceId FROM local_sync_identity").fetchone()[0])
                db.execute("DELETE FROM products WHERE id='P'")
                self.assertEqual((4, "DELETE"), db.execute("SELECT sequence,operation FROM local_change_journal ORDER BY sequence DESC LIMIT 1").fetchone())
            db.close()

if __name__ == "__main__":
    unittest.main(verbosity=2)

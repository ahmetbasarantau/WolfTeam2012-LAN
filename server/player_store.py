"""Small host-local store for each LAN player's inventory and loadout."""

import os
import sqlite3
from contextlib import contextmanager


STARTER_ITEMS = (3001, 1001, 2001, 9001)


class PlayerStore:
    def __init__(self, path):
        self.path = os.path.abspath(path)
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with self._connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS items (
                    player TEXT NOT NULL, item_id INTEGER NOT NULL,
                    PRIMARY KEY (player, item_id)
                );
                CREATE TABLE IF NOT EXISTS equipment (
                    player TEXT NOT NULL, sheet INTEGER NOT NULL, record BLOB NOT NULL,
                    PRIMARY KEY (player, sheet)
                );
                CREATE TABLE IF NOT EXISTS characters (
                    player TEXT NOT NULL, slot INTEGER NOT NULL,
                    unique_id INTEGER NOT NULL, record BLOB NOT NULL,
                    PRIMARY KEY (player, slot)
                );
            """)

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _key(player):
        player = player.strip().casefold()
        if not player or len(player) > 24:
            raise ValueError("Invalid player name")
        return player

    def load(self, player, default_equipment, default_characters):
        key = self._key(player)
        with self._connect() as db:
            saved_items = [row[0] for row in db.execute(
                "SELECT item_id FROM items WHERE player=? ORDER BY rowid", (key,))]
            equip = dict(default_equipment)
            for sheet, record in db.execute(
                    "SELECT sheet, record FROM equipment WHERE player=?", (key,)):
                if sheet in equip and len(record) == 88:
                    equip[sheet] = record
            chars = dict(default_characters)
            for slot, unique_id, record in db.execute(
                    "SELECT slot, unique_id, record FROM characters WHERE player=?", (key,)):
                if slot in chars and len(record) == 64:
                    chars[slot] = (unique_id, record)
        items = list(STARTER_ITEMS)
        items.extend(item for item in saved_items if item not in items)
        return items, equip, chars

    def add_item(self, player, item_id):
        with self._connect() as db:
            db.execute("INSERT OR IGNORE INTO items VALUES (?, ?)", (self._key(player), item_id))

    def remove_item(self, player, item_id):
        with self._connect() as db:
            db.execute("DELETE FROM items WHERE player=? AND item_id=?",
                       (self._key(player), item_id))

    def save_equipment(self, player, records):
        key = self._key(player)
        with self._connect() as db:
            db.executemany("INSERT OR REPLACE INTO equipment VALUES (?, ?, ?)",
                           [(key, sheet, record) for sheet, record in records.items()])

    def save_characters(self, player, records):
        key = self._key(player)
        with self._connect() as db:
            db.executemany("INSERT OR REPLACE INTO characters VALUES (?, ?, ?, ?)",
                           [(key, slot, unique_id, record)
                            for slot, (unique_id, record) in records.items()])

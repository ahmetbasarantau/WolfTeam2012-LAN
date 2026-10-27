import tempfile
import unittest
from pathlib import Path

from server import local_server as game
from server.player_store import PlayerStore


class PlayerStoreTests(unittest.TestCase):
    def test_inventory_and_loadout_survive_reopen_and_stay_per_player(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "players.sqlite3"
            first = PlayerStore(path)
            items, equip, chars = first.load(
                "Nostalgia", game.default_equip_records(), game.default_char_records())
            self.assertEqual(items, [3001, 1001, 2001, 9001])
            first.add_item("Nostalgia", 3125)
            equip[1] = bytes([7]) * 88
            first.save_equipment("Nostalgia", equip)
            chars[1] = (2000030005, bytes([8]) * 64)
            first.save_characters("Nostalgia", chars)

            reopened = PlayerStore(path)
            items, equip, chars = reopened.load(
                "nostalgia", game.default_equip_records(), game.default_char_records())
            self.assertIn(3125, items)
            self.assertEqual(equip[1], bytes([7]) * 88)
            self.assertEqual(chars[1], (2000030005, bytes([8]) * 64))
            other_items, other_equip, _ = reopened.load(
                "Friend", game.default_equip_records(), game.default_char_records())
            self.assertNotIn(3125, other_items)
            self.assertNotEqual(other_equip[1], equip[1])


if __name__ == "__main__":
    unittest.main()

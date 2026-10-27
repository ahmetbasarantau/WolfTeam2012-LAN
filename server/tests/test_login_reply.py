from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_server import build_login_reply


class LoginReplyTests(unittest.TestCase):
    def test_self_identity_and_following_strings_are_complete(self):
        # Cursor widths checked against our loaded CShell's 0x34161D20 reader.
        data = build_login_reply(session_id=513, udp_key=0x12345678, callsign='Nostalgia')
        self.assertEqual(struct.unpack_from('<HI', data, 1), (513, 0x12345678))
        cursor = 27
        strings = []
        for _ in range(3):
            length = data[cursor]
            cursor += 1
            self.assertLessEqual(cursor + length, len(data))
            strings.append(data[cursor:cursor+length])
            cursor += length
        self.assertEqual(strings, [b'Nostalgia', b'', b''])
        self.assertEqual(cursor, len(data))

    def test_missing_or_oversize_self_name_rejected(self):
        for name in ('', 'a'*13, '\u0131'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                build_login_reply(callsign=name)

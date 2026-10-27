from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from local_server import build_buy_item_ack, validate_buy_item_ack


class PurchaseReplyTests(unittest.TestCase):
    def test_actual_crashing_item_matches_native_reader(self):
        # Item 19601 triggered the observed stack-cookie failure at 0x342304D5.
        reply = build_buy_item_ack(19601, days=30, gold=123456, cash=654321)
        self.assertEqual(len(reply), 35)
        self.assertEqual(struct.unpack_from('<II', reply, 1), (123456, 654321))
        self.assertEqual(struct.unpack_from('<IH', reply, 9), (2000019601, 19601))
        self.assertEqual(reply[15], 14)
        self.assertEqual(reply[16:30], b'20991231235959')
        self.assertEqual(struct.unpack_from('<HBBB', reply, 30), (30, 0, 2, 1))

    def test_old_shifted_layout_and_unsafe_expiry_rejected(self):
        good = build_buy_item_ack(19601)
        old = good[:1] + struct.pack('<HI', 0, 10000000) + good[1:]
        bad_length = bytearray(good)
        bad_length[15] = 145
        bad_date = bytearray(good)
        bad_date[16] = ord('x')
        for reply in (old, bad_length, bad_date, good[:-1]):
            with self.subTest(reply=bytes(reply).hex()), self.assertRaises(ValueError):
                validate_buy_item_ack(reply, 19601)
        with self.assertRaises(ValueError):
            validate_buy_item_ack(good, 19602)

    def test_invalid_item_and_duration_rejected(self):
        for item, days in ((0, 1), (65536, 1), (1001, -1), (1001, 65536)):
            with self.subTest(item=item, days=days), self.assertRaises(ValueError):
                build_buy_item_ack(item, days=days)

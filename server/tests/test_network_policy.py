import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from network_policy import normalized_ranges


class AddressScopeTests(unittest.TestCase):
    def test_windows_cidr_range_and_mask_equivalence(self):
        self.assertEqual(normalized_ranges(['127.0.0.0/8', '::/128']),
                         normalized_ranges(['127.0.0.0-127.255.255.255', '::']))
        self.assertEqual(normalized_ranges(['192.168.1.0/255.255.255.0']),
                         normalized_ranges(['192.168.1.0/25', '192.168.1.128/25']))

    def test_broader_narrower_and_different_family_rejected(self):
        expected = normalized_ranges(['192.168.1.0/24'])
        for actual in (['192.168.0.0/16'], ['192.168.1.0/25'], ['::/0'], ['Any']):
            with self.subTest(actual=actual):
                try:
                    self.assertNotEqual(expected, normalized_ranges(actual))
                except ValueError:
                    pass

    def test_overlap_duplicates_and_ipv6_loopback_gap(self):
        self.assertEqual(normalized_ranges(['10.0.0.0/8', '10.0.0.0/9', '10.0.0.0/8']),
                         normalized_ranges(['10.0.0.0/8']))
        self.assertNotEqual(normalized_ranges(['::/128', '::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff']),
                            normalized_ranges(['::/0']))
        for value in ('10.0.0.2-10.0.0.1', '::1-127.0.0.1'):
            with self.assertRaises(ValueError):
                normalized_ranges([value])

    def test_powershell_serialized_nested_structures(self):
        wrapped = [{'value': ['0.0.0.0-126.255.255.255', '128.0.0.0-192.167.255.255', '192.168.1.0-255.255.255.255', '::/128', '::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'], 'Count': 5}]
        direct = ['0.0.0.0-126.255.255.255', '128.0.0.0-192.167.255.255', '192.168.1.0-255.255.255.255', '::', '::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff']
        self.assertEqual(normalized_ranges(wrapped), normalized_ranges(direct))
        self.assertEqual(normalized_ranges({'value': ['127.0.0.0/8'], 'Count': 1}), normalized_ranges(['127.0.0.0/8']))

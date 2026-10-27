import concurrent.futures
import ipaddress
import os
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from broker_codec import BrokerCodec
from transport import ConnectionCodec
from session_state import LobbyState, StateError
from network_policy import denied_ranges
import local_server as server


class FragmentedSocket:
    def __init__(self, data):
        self.data = data

    def recv(self, count):
        part, self.data = self.data[:1], self.data[1:]
        return part


class CodecTests(unittest.TestCase):
    def setUp(self):
        self.codec = BrokerCodec()

    def test_fragmented_and_coalesced_frames(self):
        a = self.codec.seal(0x1100, 0, b'hello')
        b = self.codec.seal(0x1300, 1, b'world')
        stream = FragmentedSocket(a+b)
        self.assertEqual(self.codec.read_frame(stream), (0x1100, 0, b'hello'+bytes(11)))
        self.assertEqual(self.codec.read_frame(stream), (0x1300, 1, b'world'+bytes(11)))
        self.assertEqual(self.codec.read_frame(stream), (None, None, None))

    def test_invalid_header_checksum(self):
        plain = bytes([1, 0, 0x13, 0, 0, 1, 0, 0])
        wire = self.codec.forward_block(self.codec.mask_prefix(plain))
        with self.assertRaises(ValueError):
            self.codec.read_frame(FragmentedSocket(wire))

    def test_truncated_streams(self):
        packet = self.codec.seal(0x1300, 1, b'hello')
        for length in (1, 7, 8, 9, len(packet)-1):
            with self.subTest(length=length), self.assertRaises(EOFError):
                self.codec.read_frame(FragmentedSocket(packet[:length]))

    def test_explicit_body_lengths_and_size_limit(self):
        packet = self.codec.seal(1, 0, b'hello')
        for body in (b'', packet[8:]+bytes(16)):
            with self.assertRaises(ValueError):
                self.codec.open(packet[:8], body)
        with self.assertRaises(ValueError):
            self.codec.seal(1, 0, bytes(4081))
        self.assertEqual(len(self.codec.seal(1, 0, bytes(4080))), 4088)

    def test_concurrent_writers_and_sequence_wrap(self):
        codec = ConnectionCodec()
        codec.sequence = 65530
        class Sink:
            def __init__(self): self.packets = []
            def sendall(self, packet): self.packets.append(packet)
        sink = Sink()
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda n: codec.write_frame(sink, 1, payload=bytes([n])), range(32)))
        stream = FragmentedSocket(b''.join(sink.packets))
        actual = [self.codec.read_frame(stream) for _ in range(32)]
        self.assertEqual([f[1] for f in actual], [(65530+n) & 65535 for n in range(32)])
        self.assertEqual({f[2][0] for f in actual}, set(range(32)))


class LobbyTests(unittest.TestCase):
    def setUp(self):
        self.state = LobbyState()
        self.ids = [self.state.connect('127.0.0.1') for _ in range(40)]

    def test_concurrent_room_ids_and_cleanup(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            rooms = list(pool.map(lambda sid: self.state.create(sid, 'Room'), self.ids))
        self.assertEqual(len({r['number'] for r in rooms}), 40)
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            list(pool.map(self.state.disconnect, self.ids))
        self.assertEqual(self.state.rooms, [])
        self.assertEqual(self.state.sessions, {})

    def test_full_invalid_duplicate_and_started_room(self):
        room = self.state.create(self.ids[0], 'Room')
        with self.assertRaises(StateError): self.state.join(self.ids[1], 65000)
        with self.assertRaises(StateError): self.state.create(self.ids[0], 'Duplicate')
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            def join(sid):
                try: self.state.join(sid, room['number']); return True
                except StateError: return False
            joined = list(pool.map(join, self.ids[1:]))
        self.assertEqual(sum(joined), 15)
        self.assertEqual(len({u['slot'] for u in room['users']}), 16)
        self.assertTrue(all(u['port'] == 0 for u in room['users']))
        room['game_started'] = True
        with self.assertRaises(StateError): self.state.join(self.ids[-1], room['number'])

    def test_master_transfer_and_slot_reuse(self):
        room = self.state.create(self.ids[0], 'Room')
        self.state.join(self.ids[1], room['number'])
        self.state.leave(self.ids[0])
        self.assertEqual(room['master_slot'], 1)
        self.state.join(self.ids[2], room['number'])
        self.assertEqual({u['slot'] for u in room['users']}, {0, 1})
        self.state.leave(self.ids[1])
        self.state.join(self.ids[1], room['number'])
        self.assertEqual(len(room['users']), 2)

    def test_udp_authentication_room_isolation_and_disconnect(self):
        a,b,c,d = self.ids[:4]
        first = self.state.create(a, 'First')
        self.state.join(b, first['number'])
        second = self.state.create(c, 'Second')
        self.state.join(d, second['number'])
        for sid in (a,b,c,d):
            endpoint = ('127.0.0.1', 30000+sid)
            self.assertFalse(self.state.identify_udp(sid, self.state.sessions[sid]['key'] ^ 1, endpoint))
            self.assertTrue(self.state.identify_udp(sid, self.state.sessions[sid]['key'], endpoint))
        self.assertEqual(self.state.relay_targets(('127.0.0.1', 9999)), [])
        self.assertEqual(self.state.relay_targets(('127.0.0.1', 30000+a)), [])
        first['game_started'] = second['game_started'] = True
        self.assertEqual(self.state.relay_targets(('127.0.0.1', 30000+a)), [('127.0.0.1', 30000+b)])
        self.state.disconnect(b)
        self.assertEqual(self.state.relay_targets(('127.0.0.1', 30000+a)), [])

    def test_session_id_wrap_avoids_live_sessions(self):
        self.state.next_id = 65535
        self.assertEqual(self.state.connect('127.0.0.1'), 65535)
        self.assertEqual(self.state.connect('127.0.0.1'), 41)


class PolicyTests(unittest.TestCase):
    def test_address_complement(self):
        for subnet in ('127.0.0.0/8', '192.168.1.0/24', '10.1.0.0/16', '172.16.1.0/24'):
            ranges = denied_ranges(subnet)[:-2]
            def blocked(address):
                number = int(ipaddress.ip_address(address))
                return any(int(ipaddress.ip_address(a)) <= number <= int(ipaddress.ip_address(b))
                           for a,b in (entry.split('-') for entry in ranges))
            self.assertTrue(blocked('8.8.8.8'))
            self.assertTrue(blocked('0.0.0.0'))
            self.assertTrue(blocked('255.255.255.255'))
            self.assertFalse(blocked('127.0.0.1'))
            net = ipaddress.ip_network(subnet)
            self.assertFalse(blocked(str(net.network_address)))
            self.assertFalse(blocked(str(net.broadcast_address)))

    def test_public_or_overbroad_subnets_rejected(self):
        for subnet in ('0.0.0.0/0', '8.8.8.0/24', '::/0', '192.168.1.1/24'):
            with self.subTest(subnet=subnet), self.assertRaises(ValueError): denied_ranges(subnet)


class ChannelIntegrationTests(unittest.TestCase):
    def test_ten_synthetic_channel_logins_and_disconnects(self):
        # This exercises server framing and cleanup; it is not a real game-client lobby test.
        for iteration in range(10):
            listener = socket.socket()
            listener.bind(('127.0.0.1',0))
            listener.listen(1)
            client = socket.create_connection(listener.getsockname())
            connection, addr = listener.accept()
            listener.close()
            thread = threading.Thread(target=server.handle_game_client, args=(connection,addr))
            thread.start()
            client.settimeout(2)
            codec = BrokerCodec()
            name = f'Player{iteration}'.encode()
            codec.write_frame(client, 0x1300, 0, bytes([0,0,0,len(name)])+name)
            self.assertEqual(codec.read_frame(client)[0], 0x1301)
            self.assertEqual(codec.read_frame(client)[0], 0x130d)
            codec.write_frame(client, 0x1600, 1)
            self.assertEqual(codec.read_frame(client)[0], 0x1601)
            # Real capture: sheet 4 previously produced payload 04, interpreted as an error.
            for sheet in range(6):
                codec.write_frame(client, 0x151E, 2 + sheet, bytes([sheet]))
                opcode, _, body = codec.read_frame(client)
                self.assertEqual(opcode, 0x151F)
                self.assertEqual(body[:2], bytes([0, sheet]))
            codec.write_frame(client, 0x1312, 2)
            self.assertEqual(codec.read_frame(client)[0], 0x1313)
            client.close()
            thread.join(2)
            self.assertFalse(thread.is_alive())
        self.assertEqual(server.state.sessions, {})
        self.assertEqual(server.connected_users, [])
        self.assertEqual(server.writers, {})


class GameFeaturesTests(unittest.TestCase):
    def test_captured_equip_request_updates_only_its_sheet(self):
        records = server.default_equip_records()
        actual = bytes.fromhex('010015a0357700e997357700b99f35770029b73577')
        actual += bytes(88 - (len(actual) - 2))
        changed = server.update_equip_records(records, actual)
        self.assertEqual(changed[0], actual[2:90])
        self.assertEqual(changed[1], records[1])
        self.assertEqual(server.build_equip_list(changed)[4:92], changed[0])
        self.assertEqual(server.build_item_check(changed)[3:91], changed[0])
        with self.assertRaises(ValueError):
            server.update_equip_records(records, actual[:-1])

    def test_captured_room_map_is_preserved(self):
        room = bytes.fromhex('26470065006c002000760065002000730069006d006400690020006b006100740069006c00210000070200000005055802640002c0001006')
        settings = server.parse_create_room_request(room)
        self.assertEqual((settings['title'], settings['map'], settings['mode']),
                         ('Gel ve simdi katil!', 7, 2))
        self.assertEqual((settings['wins'], settings['time'], settings['points'], settings['capacity']),
                         (5, 600, 100, 6))

    def test_purchased_characters_are_sent_on_relogin(self):
        body = server.build_char_item_list([30017, 30026, 1001, 30017])
        self.assertEqual(struct.unpack_from("<I", body, 5)[0], 4)
        for item_id in (30005, 30006, 30017, 30026):
            self.assertIn(struct.pack("<IH", 2000000000 + item_id, item_id), body)

    def test_death_and_revival_reply_layouts(self):
        self.assertEqual(server.build_player_dead_ack(2, 3, 5),
                         struct.pack("<BHHBBB", 0, 2, 3, 0, 5, 1))
        self.assertEqual(server.build_revival_ack(bytes([5, 1]), 2),
                         bytes([0, 1, 2, 0, 5, 0]))

    def test_purchased_character_selection_tracks_each_team(self):
        chars = server.select_purchased_character(server.default_char_records(), 30026)
        chars = server.select_purchased_character(chars, 30023)
        self.assertEqual(chars[1][0], 2000030023)
        self.assertEqual(chars[2][0], 2000030026)
        self.assertEqual(server.build_enter_game_ack({"slot": 0, "team_slot": 0, "team": 1}),
                         bytes([0, 0, 0, 1, 200]))
        self.assertEqual(server.build_change_slot_status_ack(0, 4), bytes([0, 0, 4]))

    def test_room_roster_carries_equipped_items_and_characters(self):
        room = {"number": 1, "title": "R", "password": "", "map": 7, "mode": 2,
                "wins": 5, "time": 600, "points": 100, "capacity": 6,
                "game_started": True, "master_slot": 0}
        equip = server.default_equip_records()
        sheet = bytearray(equip[0])
        struct.pack_into("<I", sheet, 0, 2000003125)
        equip[0] = bytes(sheet)
        chars = server.select_purchased_character(server.default_char_records(), 30026)
        chars = server.select_purchased_character(chars, 30023)
        member = {"login": "P", "callsign": "P", "session_id": 1,
                  "slot": 0, "team": 1, "team_slot": 0, "status": 2,
                  "level": 61, "ip": "127.0.0.1", "port": 28724,
                  "equip_records": equip, "char_records": chars}
        roster = server.build_room_roster(room, [member])
        # The four bag records sit between the two peer endpoints and char IDs.
        marker = b"\x7f\x00\x00\x01" + struct.pack(">H", 28724)
        offset = roster.index(marker) + len(marker)
        self.assertEqual(roster[offset:offset + 6], marker)
        offset += 6
        self.assertEqual(roster[offset + 1], 4)
        self.assertEqual(struct.unpack_from("<H", roster, offset + 3)[0], 3125)
        offset += 2 + 4 * 33
        self.assertEqual(roster[offset], 1)
        self.assertEqual(struct.unpack_from("<H", roster, offset + 1)[0], 30023)
        self.assertEqual(struct.unpack_from("<H", roster, offset + 4)[0], 30026)

    def test_captured_udp_registration_requires_exact_endpoint(self):
        handshake = bytes.fromhex('02190c000100b20d7e357f0000017034')
        self.assertEqual(server.parse_udp_registration(handshake, ('127.0.0.1', 28724)),
                         (1, 0x357e0db2))
        self.assertIsNone(server.parse_udp_registration(handshake, ('127.0.0.1', 28725)))
        self.assertIsNone(server.parse_udp_registration(handshake[:-1], ('127.0.0.1', 28724)))

    def test_captured_character_save_round_trips_equipment(self):
        record = bytearray([1]) + struct.pack('<I', 2000030005)
        record += struct.pack('<I', 2000030005) + bytes(60)
        update = bytes([1]) + record
        saved = server.update_char_records(server.default_char_records(), update)
        reply = server.build_char_info(saved)
        self.assertEqual(reply[0:2], b'\x00\x02')
        self.assertEqual(reply[2:8], struct.pack('<BBI', 1, 2, 2000030005))
        self.assertEqual(reply[8:72], record[5:69])
        with self.assertRaises(ValueError):
            server.update_char_records(saved, update[:-1])

    def test_power_user_ack_layout(self):
        ack = server.build_power_user_ack()
        self.assertEqual(len(ack), 18)
        self.assertEqual(ack[0], 0) # status = 0 (OK)
        enabled, grade, color = struct.unpack_from("<III", ack, 1)
        self.assertEqual(enabled, 1)
        self.assertEqual(grade, 1)
        self.assertEqual(color, 0xffd9c5b8)
        self.assertEqual(ack[13], 0) # title_len
        expiry = struct.unpack_from("<I", ack, 14)[0]
        self.assertEqual(expiry, 0x7fffffff)

    def test_box_use_ack_layout(self):
        ack = server.build_box_use_ack(2000000005, win_item=3001, gold=50000)
        self.assertEqual(len(ack), 22)
        self.assertEqual(ack[0], 0) # status = 0 (OK)
        unique_id, item1, item2, item3, item4, item5, win_idx, gold, flag1, flag2 = struct.unpack_from("<I5HBIBB", ack, 1)
        self.assertEqual(unique_id, 2000000005)
        self.assertEqual(item1, 3001)
        self.assertEqual(win_idx, 0)
        self.assertEqual(gold, 50000)
        self.assertEqual(flag1, 1)
        self.assertEqual(flag2, 0)
        result = server.build_box_result_ack(2000000005, 3001)
        self.assertEqual(len(result), 19)
        self.assertEqual(struct.unpack_from("<IH", result, 1), (2000000005, 3001))
        varied = server.build_box_use_ack(2000000005, win_item=3001,
                                          candidates=[1001, 2001, 3001, 9001, 30005], win_idx=2)
        self.assertEqual(varied[15], 2)

    def test_room_roster_header_and_green_room(self):
        room = {"number": 1, "title": "TestRoom", "password": "", "map": 1, "mode": 1,
                "wins": 5, "time": 900, "points": 100, "game_flags": 0, "heavy_limit": 0,
                "capacity": 16, "game_started": False, "master_slot": 0, "power_master": 1}
        members = [{"login": "Master", "callsign": "Master", "session_id": 1, "slot": 0,
                    "team": 1, "team_slot": 0, "status": 2, "level": 61, "ip": "127.0.0.1", "port": 40707}]
        roster = server.build_room_roster(room, members)
        # Common dispatcher consumes status before the room reader consumes state.
        self.assertEqual(roster[:2], bytes([0, 1]))
        # Master slot is 0
        self.assertEqual(roster[2], 0)
        # Room number is 1
        room_num = struct.unpack_from("<H", roster, 3)[0]
        self.assertEqual(room_num, 1)
        # Walk the variable length header before reading the native member layout.
        offset = 5
        for _ in range(2):
            offset += 1 + roster[offset]
        offset += 1 + 4 + 1
        equipment_count = roster[offset]
        offset += 1 + equipment_count + 4 + 1 + 2 + 1 + 4
        self.assertEqual(roster[offset], 1)  # member count
        offset += 1
        slot, team, row, status, sid, authority, option, level, value_a, value_b = struct.unpack_from(
            '<BBBBHBBBII', roster, offset)
        self.assertEqual((slot, team, row, status, sid, level), (0, 1, 0, 2, 1, 61))
        offset += struct.calcsize('<BBBBHBBBII')
        self.assertEqual(roster[offset:offset+7], b'\x06Master')
        # Trailing 2 bytes are 0x00, 0x00
        self.assertEqual(roster[-2:], b"\x00\x00")

    def test_field_list_green_room_flag(self):
        rooms = [{"number": 1, "title": "TestRoom", "capacity": 16, "users": []}]
        fl = server.build_field_list(rooms)
        # field_list layout: 0, 0, 0, len(rooms)
        self.assertEqual(fl[0:4], bytes([0, 0, 0, 1]))


if __name__ == '__main__':
    unittest.main()

"""
WolfTeam Localhost & LAN Server Emulator (V6 - Encrypted Wire Protocol)
Ported and reverse-engineered from wt-server (wenhaoyy/wt-server) & RageZone
"""

import socket
import threading
import http.server
import socketserver
import struct
import os
import sys
import time
import random
import datetime
import json
import ipaddress
import traceback

# Ensure server module directory is in sys.path
SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
if SERVER_DIR not in sys.path:
    sys.path.insert(0, SERVER_DIR)

from broker_codec import BrokerCodec
from transport import ConnectionCodec
from session_state import LobbyState, StateError
from player_store import PlayerStore

BASE_DIR = os.path.dirname(SERVER_DIR)
AVITAL_DIR = os.path.join(BASE_DIR, "avital")
LOG_FILE = os.path.join(SERVER_DIR, "server.log")
PLAYER_DB_PATH = os.environ.get("WOLF_PLAYER_DB", os.path.join(SERVER_DIR, "player_state.sqlite3"))
player_store = None
player_store_lock = threading.Lock()


def get_player_store():
    global player_store
    with player_store_lock:
        if player_store is None:
            player_store = PlayerStore(PLAYER_DB_PATH)
        return player_store

def log(msg):
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {msg}"
    print(line, flush=True)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except:
        pass

BIND_IP = os.environ.get("WOLF_BIND_IP", "127.0.0.1")
LAN_SUBNET = ipaddress.ip_network(os.environ.get("WOLF_LAN_SUBNET", "127.0.0.0/8"))
TRACE_DIR = os.environ.get("WOLF_TRACE_DIR")
ready_services = set()
ready_lock = threading.Lock()

def mark_ready(protocol, port):
    with ready_lock:
        ready_services.add((protocol, port))

def exclusive_socket(kind):
    sock = socket.socket(socket.AF_INET, kind)
    if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    return sock

def allowed_peer(ip):
    address = ipaddress.ip_address(ip)
    return address.is_loopback or address in LAN_SUBNET

def trace_frame(session_id, msg_id, seq, payload, direction="recv"):
    if TRACE_DIR:
        os.makedirs(TRACE_DIR, exist_ok=True)
        with open(os.path.join(TRACE_DIR, f"session-{session_id}.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps({"time": time.time(), "direction": direction, "id": msg_id,
                                "sequence": seq, "length": len(payload), "payload": payload.hex()}) + "\n")

# ----------------------------------------------------------------------
# Channels Configuration (Game-internal display only)
# STRICT RULE: Displayed ONLY in client UI. Never printed in console logs.
# Client REQUIRES at least 2 channels in WorldListAck (0x1101)
# ----------------------------------------------------------------------
CHANNELS = [
    {
        "name": "Balıkesirli Wolftimciler Derneği",
        "port": 40707,
        "open": True
    },
    {
        "name": "Balıkesirli Wolftimciler Derneği - 2",
        "port": 40707,
        "open": True
    }
]

# ----------------------------------------------------------------------
# Protocol Message IDs
# ----------------------------------------------------------------------
class Msg:
    # Broker Opcodes
    CS_BR_WORLDLIST_REQ     = 0x1100 # 4352
    CS_BR_WORLDLIST_ACK     = 0x1101 # 4353
    CS_BR_CHAINLIST_REQ     = 0x1102 # 4354 (Hello / Chain Req)
    CS_BR_CHAINLIST_ACK     = 0x1103 # 4355 (Hello / Chain Ack)
    CS_BR_RELAYLIST_REQ     = 0x1104 # 4356
    CS_BR_RELAYLIST_ACK     = 0x1105 # 4357
    CS_BR_WORLDINFO_REQ     = 0x1106 # 4358
    CS_BR_WORLDINFO_ACK     = 0x1107 # 4359
    CS_BR_DISCONNECT_REQ    = 0x1312 # 4882
    CS_BR_DISCONNECT_ACK    = 0x1313 # 4883

    # Channel / Game Opcodes
    CS_CH_LOGIN_REQ         = 0x1300 # 4864
    CS_CH_LOGIN_ACK         = 0x1301 # 4865
    CS_CH_BUDDY_ENDPOINT    = 0x050C # 1292
    CS_CH_UDPADDR_REQ       = 0x130C # 4876
    CS_CH_UDPADDR_ACK       = 0x130D # 4877
    CS_CK_UDPSUCCESS_REQ    = 0x1200 # 4608
    CS_CK_UDPSUCCESS_ACK    = 0x1201 # 4609
    CS_CK_KEEPALIVE_REQ     = 0x1202 # 4610
    CS_CK_APEX_CLIENT_INFO  = 0x120E # 4622
    CS_CH_SNSACCOUNT_REQ    = 0x1346 # 4934
    CS_CH_SNSACCOUNT_ACK    = 0x1347 # 4935
    CS_CH_EVENTSHOP_REQ     = 0x1356 # 4950
    CS_CH_EVENTSHOP_ACK     = 0x1357 # 4951
    CS_CH_USERINFO_REQ      = 0x1334 # 4916
    CS_CH_USERINFO_ACK      = 0x1335 # 4917
    CS_CH_CRESTUSERINFO_REQ = 0x134C # 4940
    CS_CH_CRESTUSERINFO_ACK = 0x134D # 4941
    CS_CH_GETPOWERUSER_REQ  = 0x1326 # 4902
    CS_CH_GETPOWERUSER_ACK  = 0x1327 # 4903
    CS_CH_ITEMCHECK_REQ     = 0x1332 # 4914
    CS_CH_ITEMCHECK_ACK     = 0x1333 # 4915
    CS_IN_ITEMLIST_REQ      = 0x1430 # 5168
    CS_IN_ITEMLIST_ACK      = 0x1431 # 5169
    CS_IN_EQUIPLIST_REQ     = 0x1432 # 5170
    CS_IN_EQUIPLIST_ACK     = 0x1433 # 5171
    CS_IN_CHARINFO_REQ      = 0x1434 # 5172
    CS_IN_CHARINFO_ACK      = 0x1435 # 5173
    CS_IN_CHARITEMLIST_REQ  = 0x1436 # 5174
    CS_IN_CHARITEMLIST_ACK  = 0x1437 # 5175
    CS_IN_ITEMPRICE_INFO_REQ    = 0x143E # 5182
    CS_IN_ITEMPRICE_INFO_ACK    = 0x143F # 5183
    CS_IN_ITEMPRICE_VERSION_REQ = 0x1440 # 5184
    CS_IN_ITEMPRICE_VERSION_ACK = 0x1441 # 5185
    CS_IN_BUYNOW_ITEMLIST_REQ   = 0x1456 # 5206
    CS_IN_BUYNOW_ITEMLIST_ACK   = 0x1457 # 5207
    CS_IN_EVENT_REWARD_LIST_REQ = 0x1458 # 5208
    CS_IN_EVENT_REWARD_LIST_ACK = 0x1459 # 5209
    CS_IN_ITEM_LIMITED_REQ      = 0x145A # 5210
    CS_IN_ITEM_LIMITED_ACK      = 0x145B # 5211
    CS_IN_PACKAGE_ITEM_LIST_REQ = 0x1462 # 5250
    CS_IN_PACKAGE_ITEM_LIST_ACK = 0x1463 # 5251
    CS_IN_BUYITEM_REQ           = 0x1404 # 5124
    CS_IN_BUYITEM_ACK           = 0x1405 # 5125
    CS_IN_SELLITEM_REQ          = 0x1408 # 5128
    CS_IN_SELLITEM_ACK          = 0x1409 # 5129
    CS_IN_UPGRADEITEM_REQ       = 0x140C # 5132
    CS_IN_UPGRADEITEM_ACK       = 0x140D # 5133
    CS_IN_EQUIP_REQ             = 0x1414 # 5140
    CS_IN_EQUIP_ACK             = 0x1415 # 5141
    CS_IN_EQUIPTITLE_REQ        = 0x1416 # 5142
    CS_IN_EQUIPTITLE_ACK        = 0x1417 # 5143
    CS_IN_DELETEDITEMS_REQ      = 0x1418 # 5144
    CS_IN_DELETEDITEMS_ACK      = 0x1419 # 5145
    CS_IN_USEITEM_REQ           = 0x141A # 5146
    CS_IN_USEITEM_ACK           = 0x141B # 5147
    CS_IN_GETGOLDCASH_REQ       = 0x141C # 5148
    CS_IN_GETGOLDCASH_ACK       = 0x141D # 5149
    CS_IN_BOXUSE_REQ            = 0x1422 # 5154 (Lucky Box / Box Use)
    CS_IN_BOXUSE_ACK            = 0x1423 # 5155 (Lucky Box / Box Use Ack)
    CS_IN_BOXRESULT_REQ         = 0x1424
    CS_IN_BOXRESULT_ACK         = 0x1425
    CS_IN_BUYBASKET_REQ         = 0x144A # 5194
    CS_IN_BUYBASKET_ACK         = 0x144B # 5195
    CS_IN_CHANGEITEM_REQ        = 0x146A # 5226
    CS_IN_CHANGEITEM_ACK        = 0x146B # 5227
    CS_CH_GETEVENTGOLDINFO_REQ  = 0x132A # 4906
    CS_CH_GETEVENTGOLDINFO_ACK  = 0x132B # 4907
    CS_CH_GETMACROMESSAGE_REQ   = 0x132C # 4908
    CS_CH_GETMACROMESSAGE_ACK   = 0x132D # 4909
    CS_CH_SELECTCHAR_REQ    = 0x1308 # 4872
    CS_CH_SELECTCHAR_ACK    = 0x1309 # 4873
    CS_FD_CRM_POPUP_REQ     = 0x16AC # 5804
    CS_FD_CRM_POPUP_ACK     = 0x16AD # 5805
    CS_FD_FIELDLIST_REQ     = 0x1600 # 5632
    CS_FD_FIELDLIST_ACK     = 0x1601 # 5633
    CS_CN_ADVERTALL_REQ     = 0x1520 # 5408
    CS_CN_ADVERTALL_ACK     = 0x1521 # 5409
    CS_CN_CHARS_ACK         = 0x1503 # 5379
    CS_CN_CHANGESHEET_REQ   = 0x151E # 5406
    CS_CN_CHANGESHEET_ACK   = 0x151F # 5407
    CS_CH_CHAT_REQ          = 0x130E # 4878
    CS_CH_CHAT_ACK          = 0x130F # 4879
    CS_FD_CREATE_REQ        = 0x160C # 5644
    CS_FD_CREATE_ACK        = 0x160D # 5645
    CS_FD_ENTER_REQ         = 0x1606 # 5638
    CS_FD_ENTER_ACK         = 0x1607 # 5639
    CS_FD_EXIT_REQ          = 0x160A # 5642
    CS_FD_EXIT_ACK          = 0x160B # 5643
    CS_FD_CHARS_ACK         = 0x1605 # 5637 (Room Roster)
    CS_FD_UDPSTART_REQ      = 0x1652 # 5714
    CS_FD_UDPSTART_ACK      = 0x1653 # 5715
    CS_FD_INITFIELD_REQ     = 0x165C
    CS_FD_INITFIELD_ACK     = 0x165D
    CS_FD_ADD_AISLOT_REQ    = 0x1674 # 5748
    CS_FD_ADD_AISLOT_ACK    = 0x1675 # 5749
    CS_FD_CHANGERULE_REQ    = 0x1614 # 5652
    CS_FD_CHANGERULE_ACK    = 0x1615 # 5653
    CS_FD_STARTGAME_REQ     = 0x1618 # 5656
    CS_FD_STARTGAME_ACK     = 0x1619 # 5657
    CS_FD_CHANGESLOTSTATUS_ACK = 0x1617 # 5655
    CS_FD_STARTROUND_REQ    = 0x1622 # 5666
    CS_FD_STARTROUND_ACK    = 0x1623 # 5667
    CS_FD_UDPREADY_REQ      = 0x163C # 5692
    CS_FD_UDPREADY_ACK      = 0x163D # 5693
    CS_FD_PLAYERDEAD_REQ    = 0x1634 # 5684
    CS_FD_PLAYERDEAD_ACK    = 0x1635 # 5685
    CS_FD_REVIVALUSER_REQ   = 0x1662 # 5730
    CS_FD_REVIVALUSER_ACK   = 0x1663 # 5731
    CS_FD_ENTERGAME_REQ     = 0x161C # 5660
    CS_FD_ENTERGAME_ACK     = 0x161D # 5661
    CS_IN_CHARSAVE_REQ      = 0x1438 # 5176
    CS_IN_CHARSAVE_ACK      = 0x1439 # 5177
    CS_CH_EVENTLIST_REQ     = 0x1338 # 4920
    CS_CH_EVENTLIST_ACK     = 0x1339 # 4921
    CS_PR_CHANGED_REQ       = 0x1730 # 5936
    CS_PR_CHANGED_ACK       = 0x1732 # 5938
    CS_PR_BATTLERESULT_REQ  = 0x173C # 5948
    CS_PR_BATTLERESULT_ACK  = 0x173D # 5949
    CS_PR_BATTLELIST_REQ    = 0x173E # 5950
    CS_PR_BATTLELIST_ACK    = 0x173F # 5951
    CS_PR_MEMBERIDS_REQ     = 0x1740 # 5952
    CS_PR_MEMBERIDS_ACK     = 0x1741 # 5953
    CS_PR_MYPRIDEINFO_REQ   = 0x1742 # 5954
    CS_PR_MYPRIDEINFO_ACK   = 0x1743 # 5955
    CS_PR_MYPRIDECHAIN_REQ  = 0x1746 # 5958
    CS_PR_MYPRIDECHAIN_ACK  = 0x1747 # 5959
    CS_PR_CHAINLIST_REQ     = 0x1748 # 5960
    CS_PR_CHAINLIST_ACK     = 0x1749 # 5961
    CS_PR_RECENTRANK_REQ    = 0x174A # 5962
    CS_PR_RECENTRANK_ACK    = 0x174B # 5963
    CS_GI_ITEM_COUNT_REQ    = 0x1A0E # 6670
    CS_GI_ITEM_COUNT_ACK    = 0x1A0F # 6671
    CS_GI_PERIOD_END_REQ    = 0x1A0C # 6668
    CS_GI_PERIOD_END_ACK    = 0x1A0D # 6669
    CS_GI_ITEM_LIST_REQ     = 0x1A02 # 6658
    CS_GI_ITEM_LIST_ACK     = 0x1A03 # 6659

# ----------------------------------------------------------------------
# Wire Payload Builders (Verified from wt-server & RageZone TR-2012)
# ----------------------------------------------------------------------
def build_chain_list_ack():
    # 0x00 status + 14 chains (14 zeros)
    return b"\x00\x0E" + (b"\x00" * 14)

def build_world_list_ack(ip_str, channels):
    ip_bytes = socket.inet_aton(ip_str)
    p = bytearray([0x00, len(channels)])
    for idx, ch in enumerate(channels):
        p.extend(ip_bytes)
        p.extend(struct.pack(">H", ch["port"])) # 2 bytes BE
        p.append(3 + idx)                       # 1 byte index
        if ch.get("open", True):
            p.extend(struct.pack("<HHHH", 2, 500, 17, 500)) # 8 bytes capacity pairs
        else:
            p.extend(struct.pack("<HHHH", 0, 0, 0, 0))
    return bytes(p)

def build_world_info_ack(channels):
    p = bytearray([0x00, len(channels)])
    for ch in channels:
        name_bytes = ch["name"].encode("utf-16le")
        p.append(len(name_bytes))
        p.extend(name_bytes)
        # server_kills (u16), server_deaths (u16), gp_bonus (u8), gold_bonus (u8)
        p.extend(struct.pack("<HHBB", 0, 0, 0, 0))
    return bytes(p)

def build_relay_list_ack(ip_str, channels, relay_port=40707):
    p = bytearray([0x00, 0x00]) # status + reserved
    p.extend(struct.pack("<I", len(channels)))
    ip_bytes = socket.inet_aton(ip_str)
    for idx in range(len(channels)):
        p.append(idx)
        p.extend(ip_bytes)
        p.extend(struct.pack(">H", relay_port))
    return bytes(p)

def build_login_reply(session_id=1, udp_key=42, gold=10000000, wc=10000000, cash=10000000, callsign="Player"):
    encoded_callsign = callsign.encode("ascii")
    if not 1 <= len(encoded_callsign) <= 12:
        raise ValueError("Login callsign must contain 1-12 ASCII characters")
    p = bytearray([0]) # status: 0 = OK
    p.extend(struct.pack("<H", session_id))
    p.extend(struct.pack("<I", udp_key))
    p.extend(struct.pack("<I", gold))
    p.extend(struct.pack("<I", 0)) # reserved
    p.extend(struct.pack("<H", 0)) # channel type (0 = Normal)
    p.extend(struct.pack("<I", wc))
    p.append(0) # muted = False
    p.append(0) # year event = 0
    p.extend(struct.pack("<I", cash))
    # LoginAck contains an ASCII callsign followed by empty ASCII/wide strings.
    # Three zero bytes leave the client's own callsign empty during channel entry.
    p.append(len(encoded_callsign))
    p.extend(encoded_callsign)
    p.extend(b"\x00\x00")
    return bytes(p)

def build_buddy_endpoint(ip_str, buddy_port=40707):
    p = bytearray([0x00])
    p.extend(socket.inet_aton(ip_str))
    p.extend(struct.pack(">H", buddy_port))
    return bytes(p)

def build_udp_addr_ack():
    return b"\x00" * 16

def build_udp_success(ip_str, port=40707):
    ip_bytes = socket.inet_aton(ip_str)
    p = bytearray([0x00])
    p.extend(ip_bytes)
    p.extend(struct.pack(">H", port))
    p.extend(ip_bytes)
    p.extend(struct.pack(">H", port))
    return bytes(p)

def build_sns_account_ack():
    return bytes([0, 0])

def build_event_shop_ack():
    # Exactly 128 bytes matching authentic 2012 WolfTeam client EventShopInfoAck
    return bytes([
        0x00, 0x03, 0x00, 0x0E, 0x32, 0x30, 0x31, 0x33, 0x30, 0x38, 0x32, 0x30, 0x30, 0x39, 0x33, 0x30, 0x30, 0x30,
        0x0E, 0x32, 0x30, 0x31, 0x33, 0x30, 0x38, 0x32, 0x33, 0x30, 0x31, 0x30, 0x30, 0x30, 0x30,
        0x00, 0x00, 0x01, 0x0E, 0x32, 0x30, 0x31, 0x32, 0x31, 0x31, 0x30, 0x35, 0x32, 0x32, 0x33, 0x30, 0x30, 0x30,
        0x0E, 0x32, 0x30, 0x31, 0x32, 0x31, 0x32, 0x33, 0x31, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
        0x00, 0x00, 0x02, 0x0E, 0x32, 0x30, 0x31, 0x32, 0x30, 0x31, 0x30, 0x31, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
        0x0E, 0x32, 0x30, 0x31, 0x32, 0x30, 0x31, 0x30, 0x31, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,
        0x00, 0x00, 0x03, 0x00, 0x01, 0x3D, 0x01, 0x00, 0x3D, 0x02, 0x01, 0x3D,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ])

def build_item_price_version_ack():
    # Exactly 16 bytes matching authentic 2012 WolfTeam client ItemPriceVersionAck
    return bytes([0x00, 0x81, 0x02, 0x00, 0x00, 0xDF, 0xB4, 0xD6, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])

def build_item_price_info_ack():
    # Exactly 10 bytes: status (1B) + versionA (4B) + versionB (4B) + changedCount (1B = 0)
    return struct.pack("<BIIB", 0, 0x0281, 0x00D6B4DF, 0)

def build_event_reward_list_ack():
    # Exactly 6 bytes: status (1B) + count (4B = 0) + flag (1B = 0)
    return struct.pack("<BIB", 0, 0, 0)

def build_item_limited_ack():
    # Exactly 16 bytes matching authentic 2012 WolfTeam client ItemLimitedAck
    return b"\x00" * 16

def build_pride_battle_result_ack():
    # Exactly 27 bytes: status (1B) + pride_id (4B) + wins (4B) + losses (4B) + win_rate (1B) + rank_pts (4B) + cur_rank (4B) + peak_rank (4B) + trailing (1B)
    return struct.pack("<BIIIBIIIB", 0, 0, 0, 0, 0, 0, 0, 0, 0)

def build_gift_item_count_ack():
    # Exactly 5 bytes: status (1B) + capacity (2B) + count (2B)
    return struct.pack("<BHH", 0, 50, 0)

def build_gift_period_end_ack():
    # Exactly 4 bytes
    return b"\x00\x00\x00\x00"

def build_event_list_ack():
    # Exactly 1 byte: 0 events
    return b"\x00"

def build_buy_now_item_list_ack():
    return bytes([0x00]) + struct.pack("<HH", 10038, 10087)

def build_currency_ack(gold=10000000, wc=10000000, cash=10000000, gp=500000, level=61):
    # ReplyGetGoldCash (0x141d): exactly 26 bytes
    return struct.pack("<BIIIIIIB", 0, gold, wc, cash, gp, 0, 1000000, level)

def build_buy_item_ack(item_id, days=30, purchase_mode=1, gold=10000000, cash=10000000):
    # Our loaded CShell reader at 0x342302B0 expects exactly 35 bytes.
    # A reserved u16 and WC balance shift the date length into the unique ID.
    if not 1 <= item_id <= 0xffff or not 0 <= days <= 0xffff:
        raise ValueError("Invalid item ID or purchase duration")
    w = bytearray()
    w.append(0) # status = 0 (success)
    w.extend(struct.pack("<II", gold, cash))
    w.extend(struct.pack("<I", 2000000000 + item_id))
    w.extend(struct.pack("<H", item_id))
    expiry = b"20991231235959" # 14 bytes
    w.append(len(expiry))
    w.extend(expiry)
    use_count = days if days > 0 else 0xffff
    w.extend(struct.pack("<HBB", use_count, 0, 2)) # count, state=0, flag=2 (notify)
    w.append(purchase_mode)
    reply = bytes(w)
    validate_buy_item_ack(reply, item_id)
    return reply

def validate_buy_item_ack(reply, item_id):
    # The legacy reader copies expiryLength into a small stack buffer.
    if len(reply) != 35 or reply[0] != 0 or reply[15] != 14 or not reply[16:30].isdigit():
        raise ValueError("Unsafe purchase reply layout")
    unique, catalog_id = struct.unpack_from("<IH", reply, 9)
    if catalog_id != item_id or unique != 2000000000 + item_id:
        raise ValueError("Purchase item identity mismatch")

def build_sell_item_ack(unique_id, gold=10000000, cash=10000000):
    # ReplySellItem (0x1409): exactly 13 bytes
    w = bytearray([0])
    w.extend(struct.pack("<III", gold, cash, unique_id))
    return bytes(w)

def build_event_gold_info_ack():
    # Exactly 30 bytes
    w = bytearray([0x00, 0x01])
    w.extend(struct.pack("<I", 0))
    w.extend(bytes(8))
    w.extend(struct.pack("<I", 10))
    w.extend(bytes(12))
    return bytes(w)

def build_user_info(callsign="Player", level=61, gold=10000000, wc=10000000, cash=10000000):
    u_callsign = callsign.encode("utf-16le")
    p = bytearray([0x00])
    p.append(len(u_callsign))
    p.extend(u_callsign)
    p.append(10) # authority
    p.append(0)  # reserved
    p.extend(struct.pack("<I", 0)) # schoolID
    p.extend(struct.pack("<I", gold))
    p.extend(struct.pack("<I", wc))
    p.extend(struct.pack("<I", cash))
    p.extend(struct.pack("<I", 0))
    p.extend(struct.pack("<I", 500000)) # gp
    p.extend(struct.pack("<I", 1000000)) # nextLevelThreshold
    p.append(0)
    p.append(level)
    p.extend(struct.pack("<I", 1)) # rank
    p.extend(struct.pack("<I", 0))
    p.extend(struct.pack("<III", 10, 100, 20)) # draws, wins, losses
    p.extend(struct.pack("<I", 0)) # pride mark
    p.extend(struct.pack("<IIIII", 500, 200, 150, 50, 80)) # kills, deaths, headshots, special, wolf
    p.extend(struct.pack("<I", 36000)) # playTimeSeconds
    p.append(0) # PT bonus
    p.extend(struct.pack("<I", 0)) # pending pride ID
    p.append(0) # empty pending pride name
    p.extend(struct.pack("<I", 0)) # pride ID
    p.extend(b"\x00\x00")
    return bytes(p)

def build_item_list(user_weapons=None):
    if user_weapons is None:
        weapons = [3001, 1001, 2001, 9001]
    else:
        weapons = user_weapons
    p = bytearray([0x00, len(weapons)])
    for idx, wid in enumerate(weapons):
        p.extend(struct.pack("<I", 2000000000 + wid))
        p.extend(struct.pack("<H", wid))
        p.extend(struct.pack("<HHHH", 0, 0, 0, 0)) # 4 upgrade slots
        expiry = b"20991231235959" # 14 bytes ASCII
        p.append(len(expiry))
        p.extend(expiry)
        p.extend(struct.pack("<HBB", 1, 0, 0)) # count=1, state=0, flag=0
    return bytes(p)

def default_equip_records():
    records = {}
    for sheet in range(4):
        rec = bytearray(88)
        # The native reader consumes primary, secondary, melee and grenade in this order.
        for offset, item in ((0, 3001), (5, 1001), (10, 2001), (15, 9001)):
            struct.pack_into("<I", rec, offset, 2000000000 + item)
        if sheet == 0:
            rec[87] = 1
        records[sheet] = bytes(rec)
    return records


def update_equip_records(records, payload):
    if not payload or payload[0] > 4 or len(payload) < 1 + payload[0] * 89:
        raise ValueError("Invalid equipment request length")
    updated = dict(records)
    for index in range(payload[0]):
        offset = 1 + 89 * index
        sheet = payload[offset]
        if sheet not in range(4) or sheet in [payload[1 + 89 * old] for old in range(index)]:
            raise ValueError("Invalid or duplicate equipment sheet")
        updated[sheet] = payload[offset + 1:offset + 89]
    return updated


def build_equip_list(records=None):
    records = default_equip_records() if records is None else records
    p = bytearray([0x00, 0x04]) # status 0, 4 sets
    for s in range(4):
        p.append(s)
        p.append(0)  # empty UTF-16 sheet name
        p.extend(records[s])
    return bytes(p)

def build_item_check(records=None):
    records = default_equip_records() if records is None else records
    p = bytearray([0x00, 0x04]) # 4 sets
    for s in range(4):
        p.append(s)
        p.extend(records[s])
    p.append(0) # 0 power items
    return bytes(p)

def default_char_records():
    records = {}
    for slot, item in ((1, 30005), (2, 30006)):
        unique = 2000000000 + item
        body = bytearray(64)
        struct.pack_into("<I", body, 0, unique)
        records[slot] = (unique, bytes(body))
    return records


def select_purchased_character(records, item_id):
    if not 30000 <= item_id < 40000:
        return records
    updated = dict(records)
    # The 2012 catalog pairs odd blue and even red character IDs.
    slot = 1 if item_id % 2 else 2
    unique = 2000000000 + item_id
    body = bytearray(updated[slot][1])
    struct.pack_into("<I", body, 0, unique)
    updated[slot] = (unique, bytes(body))
    return updated


def update_char_records(records, payload):
    if not payload or payload[0] > 2 or len(payload) < 1 + payload[0] * 69:
        raise ValueError("Invalid character save length")
    updated = dict(records)
    for index in range(payload[0]):
        offset = 1 + 69 * index
        slot = payload[offset]
        if slot not in (1, 2) or slot in [payload[1 + 69 * old] for old in range(index)]:
            raise ValueError("Invalid or duplicate character slot")
        unique = struct.unpack_from("<I", payload, offset + 1)[0]
        updated[slot] = (unique, payload[offset + 5:offset + 69])
    return updated


def build_char_info(records=None):
    records = default_char_records() if records is None else records
    p = bytearray([0x00, 0x02]) # 2 slots
    for slot, team in ((1, 2), (2, 1)):
        unique, body = records[slot]
        p.extend(struct.pack("<BBI", slot, team, unique))
        p.extend(body)
    return bytes(p)

def build_char_item_list(owned_items=None):
    chars = [30005, 30006]
    if owned_items is not None:
        chars.extend(item for item in owned_items
                     if 30000 <= item < 40000 and item not in chars)
    p = bytearray([0x00]) # status = 0
    p.extend(struct.pack("<II", 1, len(chars))) # reset=1, count
    for cid in chars:
        p.extend(struct.pack("<I", 2000000000 + cid))
        p.extend(struct.pack("<H", cid))
        expiry = b"20991231235959" # 14 bytes
        p.append(len(expiry))
        p.extend(expiry)
        p.extend(struct.pack("<HBB", 1, 0, 0)) # lifetime count=1, state=0, flag=0
    return bytes(p)

def build_select_char(ip_str, port=40707):
    p = bytearray([0x00])
    p.extend(socket.inet_aton(ip_str))
    p.extend(struct.pack(">H", port))
    p.append(0)
    return bytes(p)

def build_advert_all():
    return b"\x00" * 16

def build_channel_roster(users, sheet=0):
    p = bytearray([0x00, sheet, len(users)])
    for u in users:
        callsign_u16 = u["callsign"].encode("utf-16le")
        pride_u16 = u.get("pride", "").encode("utf-16le")
        login_ascii = u["login"].encode("ascii")
        p.append(u.get("member_id", 1))
        p.extend(struct.pack("<H", u.get("session_id", 1)))
        p.append(u.get("authority", 10))
        p.append(0)
        p.append(u.get("level", 61))
        p.extend(struct.pack("<I", 0))
        p.extend(struct.pack("<I", 0))
        p.append(len(login_ascii))
        p.extend(login_ascii)
        p.append(len(callsign_u16))
        p.extend(callsign_u16)
        p.append(len(pride_u16))
        p.extend(pride_u16)
    return bytes(p)

def build_power_user_ack():
    # Exactly 18 bytes matching CShell packet dispatcher (status=0) and unpacker at 0x3410a850:
    # status (1B = 0), enabled (4B = 1), grade (4B = 1), color (4B = 0xffd9c5b8), title_len (1B = 0), expiry (4B = 0x7fffffff)
    return bytes([0x00]) + struct.pack("<III", 1, 1, 0xffd9c5b8) + b"\x00" + struct.pack("<I", 0x7fffffff)

def build_box_use_ack(unique_id, win_item=1001, gold=10000000, candidates=None, win_idx=0):
    # Exactly 22 bytes matching CShell packet dispatcher (status=0) and unpacker at 0x3423a1e0:
    # status (1B = 0), unique_id (4B), 5 items (5 * 2B = 10B), win_idx (1B = 0), gold (4B), flag1 (1B = 1), flag2 (1B = 0)
    items = [win_item, 2001, 3001, 9001, 30005] if candidates is None else candidates
    if len(items) != 5 or not 0 <= win_idx < 5 or items[win_idx] != win_item:
        raise ValueError("Box result must point to one of five candidate items")
    return bytes([0x00]) + struct.pack("<I5HBIBB", unique_id, *items, win_idx, gold, 1, 0)


def build_box_result_ack(unique_id, win_item):
    # Native 0x3423a4f0 reader: uid, won item ID, three option words, six reserved bytes.
    return struct.pack("<BI4H6s", 0, unique_id, win_item, 0, 0, 0, bytes(6))


def build_player_dead_ack(red_score, blue_score, slot):
    return struct.pack("<BHHBBB", 0, red_score, blue_score, 0, slot, 1)


def build_revival_ack(request, team):
    if len(request) < 2:
        raise ValueError("Truncated revival request")
    return bytes([0, request[1], team, 0, request[0], 0])


def build_enter_game_ack(member):
    # Legacy client receives slot, team row, team and an entry state byte (0xC8).
    return bytes([0, member["slot"], member["team_slot"], member["team"], 200])


def build_change_slot_status_ack(slot, status):
    return bytes([0, slot, status])

def build_field_list(rooms):
    p = bytearray([0x00, 0x00, 0x00, len(rooms)])
    for r in rooms:
        title_u16 = r["title"].encode("utf-16le")
        p.extend(struct.pack("<H", r["number"]))
        p.append(len(title_u16))
        p.extend(title_u16)
        p.append(1 if r.get("has_password", False) else 0)
        p.append(1 if r.get("game_started", False) else 0)
        p.append(1) # power_master = 1 (Yeşil Oda / Green Room always active)
        p.append(r.get("map", 1))
        p.extend(struct.pack("<I", r.get("mode", 1)))
        p.append(r.get("wins", 5))
        p.extend(struct.pack("<HH", r.get("time", 900), r.get("points", 100)))
        p.append(r.get("game_flags", 0))
        p.append(r.get("weapon_flags", 0))
        p.extend(struct.pack("<H", r.get("heavy_limit", 0)))
        p.append(r.get("capacity", 16))
        p.append(len(r.get("users", [])))
        p.extend(bytes([0, 0]))
        p.append(r.get("rule_setting", 0))
        p.append(0)
        p.append(0)
    return bytes(p)

def build_create_room_ack(room_number):
    p = bytearray([0x00])
    p.extend(struct.pack("<I", 0))
    p.extend(struct.pack("<H", room_number))
    p.append(0)
    p.append(0)
    return bytes(p)


def parse_create_room_request(payload):
    # CS_FD_CREATE_REQ writes two byte-length-prefixed UTF-16 strings, map and mode.
    offset = 0
    values = []
    for _ in range(2):
        if offset >= len(payload):
            raise ValueError("Truncated room name")
        size = payload[offset]
        offset += 1
        if size % 2 or size > 80 or offset + size > len(payload):
            raise ValueError("Invalid UTF-16 room string")
        values.append(payload[offset:offset + size].decode("utf-16le"))
        offset += size
    if len(payload) < offset + 16:
        raise ValueError("Missing room settings")
    # The request continues with wins, match time, points and room capacity.
    return {"title": values[0] or "LAN Match", "password": values[1],
            "map": payload[offset], "mode": struct.unpack_from("<I", payload, offset + 1)[0],
            "wins": payload[offset + 5], "time": struct.unpack_from("<H", payload, offset + 7)[0],
            "points": payload[offset + 9], "capacity": payload[offset + 15]}


def room_bag_ids(equip_record):
    """Convert a saved 88-byte equipment sheet to the 15 item IDs in a room bag."""
    if len(equip_record) != 88:
        raise ValueError("Invalid equipment sheet")
    offsets = (0, 5, 10, 15, 19, 83) + tuple(23 + i * 4 for i in range(9))
    ids = []
    for offset in offsets:
        unique = struct.unpack_from("<I", equip_record, offset)[0]
        catalog_id = unique - 2000000000 if unique else 0
        ids.append(catalog_id if 0 <= catalog_id <= 65535 else 0)
    return ids

def build_room_roster(room, members):
    # Verified against CShell room header unpacker at 0x342308f0 and member unpacker at 0x342304e0
    title_u16 = room["title"].encode("utf-16le")
    pass_u16 = room.get("password", "").encode("utf-16le")
    p = bytearray([0])  # Common channel dispatcher consumes status before the room reader.
    p.append(2 if room.get("game_started") else 1) # state (1 = waiting, 2 = playing)
    p.append(room.get("master_slot", 0))          # master_slot (0)
    p.extend(struct.pack("<H", room["number"]))    # room_number
    p.append(len(title_u16))
    p.extend(title_u16)
    p.append(len(pass_u16))
    p.extend(pass_u16)
    p.append(room.get("map", 1))
    p.extend(struct.pack("<I", room.get("mode", 1)))
    p.append(room.get("wins", 5))
    p.append(0)                                    # extra_count = 0
    p.extend(struct.pack("<HH", room.get("time", 900), room.get("points", 100)))
    p.append(room.get("game_flags", 0))
    p.extend(struct.pack("<H", room.get("heavy_limit", 0)))
    p.append(room.get("capacity", 16))
    p.append(0)                                    # rule1
    p.append(0)                                    # rule2
    p.append(0)                                    # rule3
    p.append(1)                                    # power_master = 1 (Yeşil Oda / Green Room)
    p.append(len(members))

    for m in members:
        callsign_u16 = m["callsign"].encode("utf-16le")
        pride_u16 = m.get("pride", "").encode("utf-16le")
        login_ascii = m["login"].encode("ascii")
        slot = m.get("slot", 0)
        is_master = 2 if slot == room.get("master_slot", 0) else 0

        p.append(slot)
        p.append(m.get("team", slot % 2 + 1))
        p.append(m.get("team_slot", slot // 2))
        p.append(m.get("status", is_master))
        p.extend(struct.pack("<H", m.get("session_id", 1)))
        p.append(m.get("authority", 0))
        p.append(0)                               # member option
        p.append(m.get("level", 61))              # native reader consumes one byte
        p.extend(struct.pack("<II", 50000, 0))
        p.append(len(login_ascii))
        p.extend(login_ascii)
        p.append(len(callsign_u16))
        p.extend(callsign_u16)
        p.append(len(pride_u16))
        p.extend(pride_u16)

        ip_bytes = socket.inet_aton(m.get("ip", "127.0.0.1"))
        port = m.get("port", 40707) or 40707
        p.extend(ip_bytes)
        p.extend(struct.pack(">H", port))
        p.extend(ip_bytes)
        p.extend(struct.pack(">H", port))

        p.append(m.get("room_status", 1))
        equipment = m.get("equip_records") or default_equip_records()
        p.append(4)                               # four saved loadout sheets
        for sheet in range(4):
            p.append(sheet)
            p.extend(struct.pack("<15H", *room_bag_ids(equipment[sheet])))
            p.extend(struct.pack("<H", 0))
        characters = m.get("char_records") or default_char_records()
        blue_uid = characters[1][0]
        red_uid = characters[2][0]
        blue_id = blue_uid - 2000000000 if blue_uid else 30005
        red_id = red_uid - 2000000000 if red_uid else 30006
        if not 0 <= blue_id <= 65535:
            blue_id = 30005
        if not 0 <= red_id <= 65535:
            red_id = 30006
        p.append(1)                               # blue_chars_count = 1
        p.extend(struct.pack("<H", blue_id))
        p.append(1)                               # red_chars_count = 1
        p.extend(struct.pack("<H", red_id))
        p.extend(struct.pack("<H", 0))            # extra_word
        p.append(1)                               # power_user = 1 (Özel Kurt Yeteneği)

    p.append(0)                                   # trailing byte 1
    p.append(0)                                   # trailing byte 2
    return bytes(p)

# ----------------------------------------------------------------------
# Local HTTP Server (Port 80)
# ----------------------------------------------------------------------
class LocalHTTPHandler(http.server.SimpleHTTPRequestHandler):
    def parse_request(self):
        if not super().parse_request():
            return False
        if not allowed_peer(self.client_address[0]):
            self.send_error(403)
            return False
        return True

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        log(f"[HTTP 80] GET {self.path}")
        update_file = os.path.join(AVITAL_DIR, "update.u")
        if self.path in ("/update.u", "/apex/wolfteam/psserver/web/update.u") and os.path.exists(update_file):
            with open(update_file, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_error(404, "Unknown local service endpoint")

    def do_POST(self):
        self.send_error(405)

    def do_HEAD(self):
        self.send_error(405)


def start_http_server(port=80):
    try:
        class ReusableTCPServer(socketserver.TCPServer):
            allow_reuse_address = False
            def server_bind(self):
                if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                super().server_bind()
        with ReusableTCPServer((BIND_IP, port), LocalHTTPHandler) as httpd:
            mark_ready("tcp", port)
            log(f"[+] HTTP Server active on port {port}")
            httpd.serve_forever()
    except Exception as e:
        log(f"[!] HTTP Server warning on port {port}: {e}")

# ----------------------------------------------------------------------
# Broker Server (Port 40706 & Port 30706 TCP)
# ----------------------------------------------------------------------
def handle_broker_client(conn, addr, local_port):
    log(f"[Broker:{local_port}] Client connected from {addr}")
    codec = ConnectionCodec()
    server_ip_str = conn.getsockname()[0]
    if server_ip_str == "0.0.0.0":
        server_ip_str = "127.0.0.1"

    seq_out = 0
    try:
        conn.settimeout(60.0)
        while True:
            msg_id, seq_in, payload = codec.read_frame(conn)
            if msg_id is None:
                log(f"[Broker:{local_port}] Client {addr} disconnected.")
                break

            log(f"[Broker:{local_port}] RECV 0x{msg_id:04X} (seq: {seq_in}, payload: {len(payload)}B)")

            # 1. 0x1102 (ChainListReq) -> 0x1103 (ChainListAck)
            if msg_id == Msg.CS_BR_CHAINLIST_REQ:
                resp = build_chain_list_ack()
                codec.write_frame(conn, Msg.CS_BR_CHAINLIST_ACK, seq_out, resp)
                seq_out += 1
                log(f"[Broker:{local_port}] SENT 0x1103 (ChainListAck - 14 chains)")

            # 2. 0x1100 (WorldListReq) -> 0x1101 (WorldListAck)
            elif msg_id == Msg.CS_BR_WORLDLIST_REQ:
                resp = build_world_list_ack(server_ip_str, CHANNELS)
                codec.write_frame(conn, Msg.CS_BR_WORLDLIST_ACK, seq_out, resp)
                seq_out += 1
                log(f"[Broker:{local_port}] SENT 0x1101 (WorldListAck - {len(CHANNELS)} channels)")

            # 3. 0x1106 (WorldInfoReq) -> 0x1107 (WorldInfoAck)
            elif msg_id == Msg.CS_BR_WORLDINFO_REQ:
                resp = build_world_info_ack(CHANNELS)
                codec.write_frame(conn, Msg.CS_BR_WORLDINFO_ACK, seq_out, resp)
                seq_out += 1
                log(f"[Broker:{local_port}] SENT 0x1107 (WorldInfoAck - {len(CHANNELS)} channels)")

            # 4. 0x1104 (RelayListReq) -> 0x1105 (RelayListAck)
            elif msg_id == Msg.CS_BR_RELAYLIST_REQ:
                resp = build_relay_list_ack(server_ip_str, CHANNELS, relay_port=40707)
                codec.write_frame(conn, Msg.CS_BR_RELAYLIST_ACK, seq_out, resp)
                seq_out += 1
                log(f"[Broker:{local_port}] SENT 0x1105 (RelayListAck)")

            # 5. 0x1312 (DisconnectReq) -> 0x1313 (DisconnectAck)
            elif msg_id == Msg.CS_BR_DISCONNECT_REQ:
                codec.write_frame(conn, Msg.CS_BR_DISCONNECT_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[Broker:{local_port}] SENT 0x1313 (DisconnectAck - transitioning to GameServer!)")
                break

            else:
                codec.write_frame(conn, msg_id + 1, seq_out, b"\x00")
                seq_out += 1
                log(f"[Broker:{local_port}] SENT generic ack for 0x{msg_id:04X}")

    except Exception as e:
        log(f"[Broker:{local_port}] Connection closed: {e}")
    finally:
        conn.close()

def start_broker_server(port):
    try:
        s = exclusive_socket(socket.SOCK_STREAM)
        s.bind((BIND_IP, port))
        s.listen(10)
        mark_ready("tcp", port)
        log(f"[+] Broker Server active on TCP port {port}")
        while True:
            conn, addr = s.accept()
            if not allowed_peer(addr[0]):
                conn.close()
                continue
            threading.Thread(target=handle_broker_client, args=(conn, addr, port), daemon=True).start()
    except Exception as e:
        log(f"[!] Broker Server bind failed on port {port}: {e}")

# ----------------------------------------------------------------------
# Game / Channel Server (Port 40707 TCP)
# ----------------------------------------------------------------------
state = LobbyState()
active_rooms = state.rooms
rooms_lock = state.lock
connected_users = []
users_lock = threading.RLock()
writers = {}
publish_lock = threading.Lock()

def send_to(sid, msg_id, payload):
    with users_lock:
        writer = writers.get(sid)
    if writer:
        conn, codec = writer
        try:
            codec.write_frame(conn, msg_id, payload=payload)
        except OSError:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

def publish_rooms():
    # Prevent an older snapshot from overtaking a newer room broadcast.
    with publish_lock:
        _publish_rooms()

def _publish_rooms():
    field_list = build_field_list(state.snapshot())
    with users_lock:
        targets = list(writers)
    for sid in targets:
        send_to(sid, Msg.CS_FD_FIELDLIST_ACK, field_list)
    with state.lock:
        import copy
        rooms = copy.deepcopy(state.rooms)
    for room in rooms:
        roster = build_room_roster(room, room["users"])
        for member in room["users"]:
            send_to(member["session_id"], Msg.CS_FD_CHARS_ACK, roster)


def handle_game_client(conn, addr):
    log(f"[GameServer 40707] Client connected from {addr}")
    codec = ConnectionCodec()
    server_ip_str = conn.getsockname()[0]
    if server_ip_str == "0.0.0.0":
        server_ip_str = "127.0.0.1"

    seq_out = 0
    session_id = state.connect(addr[0])
    codec.trace = lambda msg, seq, data: trace_frame(session_id, msg, seq, data, "send")
    logged_in = False
    current_callsign = "Player"
    current_room = None
    owned_weapons = [3001, 1001, 2001, 9001]
    equip_records = default_equip_records()
    char_records = default_char_records()
    pending_box_result = None

    try:
        conn.settimeout(180.0)
        while True:
            msg_id, seq_in, payload = codec.read_frame(conn)
            if msg_id is None:
                log(f"[GameServer 40707] Client {addr} disconnected.")
                break

            log(f"[GameServer 40707] RECV 0x{msg_id:04X} (seq: {seq_in}, payload: {len(payload)}B)")

            trace_frame(session_id, msg_id, seq_in, payload)
            if not logged_in and msg_id != Msg.CS_CH_LOGIN_REQ:
                raise ValueError("Login required before channel requests")
            if logged_in and msg_id == Msg.CS_CH_LOGIN_REQ:
                raise ValueError("Duplicate login")

            # 1. LoginReq (0x1300)
            if msg_id == Msg.CS_CH_LOGIN_REQ:
                try:
                    if len(payload) >= 5:
                        hash_len = payload[2]
                        name_len_offset = 3 + hash_len
                        if name_len_offset < len(payload):
                            name_len = payload[name_len_offset]
                            name_offset = name_len_offset + 1
                            if name_offset + name_len <= len(payload):
                                parsed_nick = payload[name_offset:name_offset+name_len].decode("ascii", errors="ignore").strip()
                                if parsed_nick:
                                    current_callsign = parsed_nick[:12]
                except Exception as e:
                    log(f"[GameServer 40707] LoginReq parse warning: {e}")

                owned_weapons, equip_records, char_records = get_player_store().load(
                    current_callsign, default_equip_records(), default_char_records())
                state.set_loadout(session_id, equip_records, char_records)
                logged_in = True
                with state.lock:
                    state.sessions[session_id]["callsign"] = current_callsign
                    udp_key = state.sessions[session_id]["key"]
                with users_lock:
                    writers[session_id] = (conn, codec)
                login_reply = build_login_reply(session_id=session_id, udp_key=udp_key, callsign=current_callsign)
                codec.write_frame(conn, Msg.CS_CH_LOGIN_ACK, seq_out, login_reply)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1301 (LoginAck for {current_callsign})")

                # Proactively send UDP Address Ack (0x130D) so client UDP check completes immediately
                codec.write_frame(conn, Msg.CS_CH_UDPADDR_ACK, seq_out, build_udp_addr_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x130D (UdpAddrAck)")

                with users_lock:
                    connected_users.append({
                        "login": current_callsign,
                        "callsign": current_callsign,
                        "session_id": session_id,
                        "member_id": next(n for n in range(1, 256) if n not in {u["member_id"] for u in connected_users}),
                        "authority": 10,
                        "level": 61,
                        "ip": addr[0],
                        "port": 0
                    })

            # 2. UDPAddressReq (0x130C)
            elif msg_id == Msg.CS_CH_UDPADDR_REQ or msg_id == Msg.CS_CK_KEEPALIVE_REQ:
                codec.write_frame(conn, Msg.CS_CH_UDPADDR_ACK, seq_out, build_udp_addr_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x130D (UdpAddrAck on request)")

            # 3. UDPSuccessReq (0x1200)
            elif msg_id == Msg.CS_CK_UDPSUCCESS_REQ:
                udp_succ = build_udp_success(server_ip_str, port=40707)
                codec.write_frame(conn, Msg.CS_CK_UDPSUCCESS_ACK, seq_out, udp_succ)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1201 (UdpSuccessAck)")

            # 4. Apex Keepalive / Telemetry (0x120E)
            elif msg_id == Msg.CS_CK_APEX_CLIENT_INFO:
                # Telemetry heartbeat - acknowledged silently without unknown opcode reply
                pass

            # 5. SNSAccountReq (0x1346)
            elif msg_id == Msg.CS_CH_SNSACCOUNT_REQ:
                codec.write_frame(conn, Msg.CS_CH_SNSACCOUNT_ACK, seq_out, build_sns_account_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1347 (SnsAccountAck)")

            # 6. EventShopReq (0x1356)
            elif msg_id == Msg.CS_CH_EVENTSHOP_REQ:
                codec.write_frame(conn, Msg.CS_CH_EVENTSHOP_ACK, seq_out, build_event_shop_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1357 (EventShopAck)")

            # 7. CRM PopUpReq (0x16AC)
            elif msg_id == Msg.CS_FD_CRM_POPUP_REQ:
                codec.write_frame(conn, Msg.CS_FD_CRM_POPUP_ACK, seq_out, b"\x00" * 16)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x16AD (PopUpAck)")

            # 4. UserInfoReq (0x1334)
            elif msg_id == Msg.CS_CH_USERINFO_REQ:
                u_info = build_user_info(callsign=current_callsign)
                codec.write_frame(conn, Msg.CS_CH_USERINFO_ACK, seq_out, u_info)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1335 (UserInfoAck)")

            # 5. CrestUserReq (0x134C)
            elif msg_id == Msg.CS_CH_CRESTUSERINFO_REQ:
                crest_reply = struct.pack("<III", 0x3000000, 0x1000000, 0x2000000)
                codec.write_frame(conn, Msg.CS_CH_CRESTUSERINFO_ACK, seq_out, crest_reply)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x134D (CrestUserInfoAck)")

            # 6. PowerUserReq (0x1326)
            elif msg_id == Msg.CS_CH_GETPOWERUSER_REQ:
                codec.write_frame(conn, Msg.CS_CH_GETPOWERUSER_ACK, seq_out, build_power_user_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1327 (GetPowerUserInfoAck - Power User enabled)")

            # 7. ItemCheckReq (0x1332)
            elif msg_id == Msg.CS_CH_ITEMCHECK_REQ:
                item_chk = build_item_check(equip_records)
                codec.write_frame(conn, Msg.CS_CH_ITEMCHECK_ACK, seq_out, item_chk)
                seq_out += 1

            # 8. ItemListReq (0x1430)
            elif msg_id == Msg.CS_IN_ITEMLIST_REQ:
                item_list = build_item_list(owned_weapons)
                codec.write_frame(conn, Msg.CS_IN_ITEMLIST_ACK, seq_out, item_list)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1431 (ItemListAck - {len(owned_weapons)} items)")

            # GetGoldCashReq (0x141C)
            elif msg_id == Msg.CS_IN_GETGOLDCASH_REQ:
                codec.write_frame(conn, Msg.CS_IN_GETGOLDCASH_ACK, seq_out, build_currency_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x141D (GetGoldCashAck)")

            # BuyItemReq (0x1404)
            elif msg_id == Msg.CS_IN_BUYITEM_REQ:
                try:
                    if len(payload) < 6:
                        raise ValueError("Truncated purchase request")
                    item_id, days = struct.unpack_from("<HH", payload)
                    purchase_mode = payload[5]
                    reply_buy = build_buy_item_ack(item_id, days=days, purchase_mode=purchase_mode)
                except ValueError as e:
                    log(f"[GameServer 40707] BuyItem parse error: {e}")
                    codec.write_frame(conn, Msg.CS_IN_BUYITEM_ACK, payload=b"\x01")
                    continue

                if item_id not in owned_weapons:
                    owned_weapons.append(item_id)
                get_player_store().add_item(current_callsign, item_id)
                if 30000 <= item_id < 40000:
                    char_records = select_purchased_character(char_records, item_id)
                    get_player_store().save_characters(current_callsign, char_records)
                    state.set_loadout(session_id, equip_records, char_records)
                    if current_room:
                        publish_rooms()

                codec.write_frame(conn, Msg.CS_IN_BUYITEM_ACK, seq_out, reply_buy)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1405 (BuyItemAck for item {item_id}, {days} days)")

            # SellItemReq (0x1408)
            elif msg_id == Msg.CS_IN_SELLITEM_REQ:
                unique_id = 0
                try:
                    if len(payload) >= 4:
                        unique_id = struct.unpack_from("<I", payload, 0)[0]
                        item_id = unique_id - 2000000000
                        if item_id in owned_weapons:
                            owned_weapons.remove(item_id)
                            get_player_store().remove_item(current_callsign, item_id)
                except Exception as e:
                    log(f"[GameServer 40707] SellItem parse error: {e}")

                reply_sell = build_sell_item_ack(unique_id)
                codec.write_frame(conn, Msg.CS_IN_SELLITEM_ACK, seq_out, reply_sell)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1409 (SellItemAck for unique_id {unique_id})")

            # BuyBasketReq (0x144A)
            elif msg_id == Msg.CS_IN_BUYBASKET_REQ:
                codec.write_frame(conn, Msg.CS_IN_BUYBASKET_ACK, seq_out, bytes([0, 0]) + struct.pack("<III", 10000000, 10000000, 10000000))
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x144B (BuyBasketAck)")

            # BoxUseReq (0x1422) - Lucky Box Opening
            elif msg_id == Msg.CS_IN_BOXUSE_REQ:
                unique_id = 2000000001
                if len(payload) >= 4:
                    unique_id = struct.unpack_from("<I", payload, 0)[0]
                reward_pool = [1001, 2001, 3001, 9001, 10038, 10087, 30005, 30006]
                candidates = random.sample(reward_pool, 5)
                win_idx = random.randrange(5)
                win_item = candidates[win_idx]
                pending_box_result = (unique_id, win_item)
                box_ack = build_box_use_ack(unique_id, win_item=win_item,
                                            candidates=candidates, win_idx=win_idx)
                codec.write_frame(conn, Msg.CS_IN_BOXUSE_ACK, seq_out, box_ack)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1423 (BoxUseAck: unique_id {unique_id}, won {win_item})")

            elif msg_id == Msg.CS_IN_BOXRESULT_REQ:
                if len(payload) < 4 or pending_box_result is None:
                    codec.write_frame(conn, Msg.CS_IN_BOXRESULT_ACK, seq_out, b"\x01")
                else:
                    unique_id = struct.unpack_from("<I", payload)[0]
                    expected_id, win_item = pending_box_result
                    if unique_id != expected_id:
                        codec.write_frame(conn, Msg.CS_IN_BOXRESULT_ACK, seq_out, b"\x01")
                    else:
                        get_player_store().add_item(current_callsign, win_item)
                        if win_item not in owned_weapons:
                            owned_weapons.append(win_item)
                        codec.write_frame(conn, Msg.CS_IN_BOXRESULT_ACK, seq_out,
                                          build_box_result_ack(unique_id, win_item))
                        pending_box_result = None
                        log(f"[GameServer 40707] SENT 0x1425 (BoxResultAck: item {win_item})")
                seq_out += 1

            # EquipReq (0x1414)
            elif msg_id == Msg.CS_IN_EQUIP_REQ:
                equip_records = update_equip_records(equip_records, payload)
                get_player_store().save_equipment(current_callsign, equip_records)
                state.set_loadout(session_id, equip_records, char_records)
                if current_room:
                    publish_rooms()
                codec.write_frame(conn, Msg.CS_IN_EQUIP_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1415 (EquipAck)")

            # EquipTitleReq (0x1416)
            elif msg_id == Msg.CS_IN_EQUIPTITLE_REQ:
                codec.write_frame(conn, Msg.CS_IN_EQUIPTITLE_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1417 (EquipTitleAck)")

            # DeletedItemsReq (0x1418)
            elif msg_id == Msg.CS_IN_DELETEDITEMS_REQ:
                codec.write_frame(conn, Msg.CS_IN_DELETEDITEMS_ACK, seq_out, b"\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1419 (DeletedItemsAck)")

            # UseItemReq (0x141A)
            elif msg_id == Msg.CS_IN_USEITEM_REQ:
                codec.write_frame(conn, Msg.CS_IN_USEITEM_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x141B (UseItemAck)")

            # ChangeItemReq (0x146A)
            elif msg_id == Msg.CS_IN_CHANGEITEM_REQ:
                codec.write_frame(conn, Msg.CS_IN_CHANGEITEM_ACK, seq_out, payload)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x146B (ChangeItemAck)")

            # EventRewardListReq (0x1458)
            elif msg_id == Msg.CS_IN_EVENT_REWARD_LIST_REQ:
                codec.write_frame(conn, Msg.CS_IN_EVENT_REWARD_LIST_ACK, seq_out, build_event_reward_list_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1459 (EventRewardListAck)")

            # ItemLimitedReq (0x145A)
            elif msg_id == Msg.CS_IN_ITEM_LIMITED_REQ:
                codec.write_frame(conn, Msg.CS_IN_ITEM_LIMITED_ACK, seq_out, build_item_limited_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x145B (ItemLimitedAck)")

            # ItemPriceVersionReq (0x1440)
            elif msg_id == Msg.CS_IN_ITEMPRICE_VERSION_REQ:
                codec.write_frame(conn, Msg.CS_IN_ITEMPRICE_VERSION_ACK, seq_out, build_item_price_version_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1441 (ItemPriceVersionAck)")

            # ItemPriceInfoReq (0x143E)
            elif msg_id == Msg.CS_IN_ITEMPRICE_INFO_REQ:
                codec.write_frame(conn, Msg.CS_IN_ITEMPRICE_INFO_ACK, seq_out, build_item_price_info_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x143F (ItemPriceInfoAck)")

            # BuyNowItemListReq (0x1456)
            elif msg_id == Msg.CS_IN_BUYNOW_ITEMLIST_REQ:
                codec.write_frame(conn, Msg.CS_IN_BUYNOW_ITEMLIST_ACK, seq_out, build_buy_now_item_list_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1457 (BuyNowItemListAck)")

            # PackageItemListReq (0x1462)
            elif msg_id == Msg.CS_IN_PACKAGE_ITEM_LIST_REQ:
                codec.write_frame(conn, Msg.CS_IN_PACKAGE_ITEM_LIST_ACK, seq_out, b"\x00" * 16)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1463 (PackageItemListAck)")

            # GetEventGoldInfoReq (0x132A)
            elif msg_id == Msg.CS_CH_GETEVENTGOLDINFO_REQ:
                codec.write_frame(conn, Msg.CS_CH_GETEVENTGOLDINFO_ACK, seq_out, build_event_gold_info_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x132B (GetEventGoldInfoAck)")

            # GetMacroMessageReq (0x132C)
            elif msg_id == Msg.CS_CH_GETMACROMESSAGE_REQ:
                codec.write_frame(conn, Msg.CS_CH_GETMACROMESSAGE_ACK, seq_out, b"\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x132D (GetMacroMessageAck)")

            # 9. EquipListReq (0x1432)
            elif msg_id == Msg.CS_IN_EQUIPLIST_REQ:
                equip_list = build_equip_list(equip_records)
                codec.write_frame(conn, Msg.CS_IN_EQUIPLIST_ACK, seq_out, equip_list)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1433 (EquipListAck)")

            # 10. CharInfoReq (0x1434)
            elif msg_id == Msg.CS_IN_CHARINFO_REQ:
                char_info = build_char_info(char_records)
                codec.write_frame(conn, Msg.CS_IN_CHARINFO_ACK, seq_out, char_info)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1435 (CharInfoAck)")

            # 11. CharItemListReq (0x1436)
            elif msg_id == Msg.CS_IN_CHARITEMLIST_REQ:
                char_items = build_char_item_list(owned_weapons)
                codec.write_frame(conn, Msg.CS_IN_CHARITEMLIST_ACK, seq_out, char_items)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1437 (CharItemListAck)")

            # 12. SelectCharReq (0x1308)
            elif msg_id == Msg.CS_CH_SELECTCHAR_REQ:
                sel_char = build_select_char(server_ip_str, port=40707)
                codec.write_frame(conn, Msg.CS_CH_SELECTCHAR_ACK, seq_out, sel_char)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1309 (SelectCharAck)")

            # 13. AdvertAllReq (0x1520)
            elif msg_id == Msg.CS_CN_ADVERTALL_REQ:
                # 1. Channel roster first (0x1503)
                with users_lock:
                    roster = build_channel_roster(connected_users)
                codec.write_frame(conn, Msg.CS_CN_CHARS_ACK, seq_out, roster)
                seq_out += 1

                # 2. AdvertAllAck second (0x1521)
                adv = build_advert_all()
                codec.write_frame(conn, Msg.CS_CN_ADVERTALL_ACK, seq_out, adv)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1503 (ChannelRoster) & 0x1521 (AdvertAllAck)")

            # 14. ChangeSheetReq (0x151E)
            elif msg_id == Msg.CS_CN_CHANGESHEET_REQ:
                sheet = payload[0] if len(payload) > 0 else 0
                # Dispatcher consumes status; 0x341d0f00 then consumes the sheet byte.
                codec.write_frame(conn, Msg.CS_CN_CHANGESHEET_ACK, seq_out, bytes([0, sheet]))
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x151F (ChangeSheetAck for sheet {sheet})")

            # 15. FieldListReq (0x1600)
            elif msg_id == Msg.CS_FD_FIELDLIST_REQ:
                with rooms_lock:
                    field_list = build_field_list(active_rooms)
                codec.write_frame(conn, Msg.CS_FD_FIELDLIST_ACK, seq_out, field_list)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1601 (FieldListAck - {len(active_rooms)} rooms)")

            elif msg_id == Msg.CS_FD_CREATE_REQ:
                settings = parse_create_room_request(payload)
                try:
                    current_room = state.create(session_id, settings["title"])
                except StateError as exc:
                    log(f"Room creation rejected: {exc}")
                    codec.write_frame(conn, Msg.CS_FD_CREATE_ACK, seq_out, b"\x01")
                    seq_out += 1
                    continue
                current_room.update({key: settings[key] for key in
                                     ("password", "map", "mode", "wins", "time", "points", "capacity")})
                codec.write_frame(conn, Msg.CS_FD_CREATE_ACK, seq_out,
                                  build_create_room_ack(current_room["number"]))
                seq_out += 1
                publish_rooms()

            elif msg_id == Msg.CS_FD_ENTER_REQ:
                if len(payload) < 2:
                    raise ValueError("Missing room number")
                number = struct.unpack_from("<H", payload)[0]
                try:
                    current_room = state.join(session_id, number)
                except StateError as exc:
                    log(f"Room entry rejected: {exc}")
                    codec.write_frame(conn, Msg.CS_FD_ENTER_ACK, seq_out, b"\x01")
                    seq_out += 1
                    continue
                codec.write_frame(conn, Msg.CS_FD_ENTER_ACK, seq_out, struct.pack("<BH", 0, number))
                seq_out += 1
                publish_rooms()

            elif msg_id == Msg.CS_FD_EXIT_REQ:
                state.leave(session_id)
                current_room = None
                codec.write_frame(conn, Msg.CS_FD_EXIT_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x160B (ExitRoomAck)")
                publish_rooms()

            elif msg_id == Msg.CS_FD_STARTGAME_REQ:
                if current_room:
                    current_room["game_started"] = True
                codec.write_frame(conn, Msg.CS_FD_STARTGAME_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1619 (StartGameAck - Solo Match Started)")
                publish_rooms()

            elif msg_id == Msg.CS_FD_INITFIELD_REQ:
                # Native 0x341ed4d0 reader consumes no body after the common status.
                codec.write_frame(conn, Msg.CS_FD_INITFIELD_ACK, seq_out, b"\x00")
                seq_out += 1

            elif msg_id == Msg.CS_FD_UDPSTART_REQ:
                # Native 0x341cdc70 reader consumes one UDP state byte.
                slot = next((m["slot"] for m in current_room["users"]
                             if m["session_id"] == session_id), 0) if current_room else 0
                codec.write_frame(conn, Msg.CS_FD_UDPSTART_ACK, seq_out, bytes([0, slot]))
                seq_out += 1

            elif msg_id == Msg.CS_FD_UDPREADY_REQ:
                slot = next((m["slot"] for m in current_room["users"]
                             if m["session_id"] == session_id), 0) if current_room else 0
                ready = payload[0] if payload else 1
                codec.write_frame(conn, Msg.CS_FD_UDPREADY_ACK, seq_out,
                                  bytes([0, slot, ready]))
                seq_out += 1

            elif msg_id == Msg.CS_FD_STARTROUND_REQ:
                if not current_room or not current_room.get("game_started"):
                    codec.write_frame(conn, Msg.CS_FD_STARTROUND_ACK, seq_out, b"\x01")
                else:
                    member = next((m for m in current_room["users"]
                                   if m["session_id"] == session_id), None)
                    if member:
                        member["room_status"] = 4
                        codec.write_frame(conn, Msg.CS_FD_CHANGESLOTSTATUS_ACK, seq_out,
                                          build_change_slot_status_ack(member["slot"], 4))
                        seq_out += 1
                        codec.write_frame(conn, Msg.CS_FD_ENTERGAME_ACK, seq_out,
                                          build_enter_game_ack(member))
                        seq_out += 1
                    round_number = current_room.setdefault("round_number", 1)
                    remaining_time = min(max(current_room.get("time", 900) + 5, 0), 65535)
                    codec.write_frame(conn, Msg.CS_FD_STARTROUND_ACK, seq_out,
                                      struct.pack("<BBH", 0, round_number, remaining_time))
                seq_out += 1

            elif msg_id == Msg.CS_FD_PLAYERDEAD_REQ:
                if current_room:
                    member = next((m for m in current_room["users"]
                                   if m["session_id"] == session_id), None)
                    if member:
                        if member["team"] == 1:
                            current_room["blue_score"] = current_room.get("blue_score", 0) + 1
                        else:
                            current_room["red_score"] = current_room.get("red_score", 0) + 1
                        body = build_player_dead_ack(current_room.get("red_score", 0),
                                                     current_room.get("blue_score", 0),
                                                     member["slot"])
                        for participant in current_room["users"]:
                            send_to(participant["session_id"], Msg.CS_FD_PLAYERDEAD_ACK, body)
                        log(f"[GameServer 40707] SENT 0x1635 (PlayerDeadAck: slot {member['slot']})")

            elif msg_id == Msg.CS_FD_REVIVALUSER_REQ:
                if current_room and len(payload) >= 2:
                    member = next((m for m in current_room["users"]
                                   if m["session_id"] == session_id), None)
                    if member:
                        body = build_revival_ack(payload, member["team"])
                        for participant in current_room["users"]:
                            send_to(participant["session_id"], Msg.CS_FD_REVIVALUSER_ACK, body)

            # 20. EnterGameReq (0x161C)
            elif msg_id == Msg.CS_FD_ENTERGAME_REQ:
                member = next((m for m in current_room["users"]
                               if m["session_id"] == session_id), None) if current_room else None
                codec.write_frame(conn, Msg.CS_FD_ENTERGAME_ACK, seq_out,
                                  build_enter_game_ack(member) if member else b"\x01")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x161D (EnterGameAck - Success)")

            # 21. ChatReq (0x130E)
            elif msg_id == Msg.CS_CH_CHAT_REQ:
                codec.write_frame(conn, Msg.CS_CH_CHAT_ACK, seq_out, payload)
                seq_out += 1

            # 22. DisconnectReq (0x1312)
            elif msg_id == Msg.CS_BR_DISCONNECT_REQ:
                codec.write_frame(conn, Msg.CS_BR_DISCONNECT_ACK, seq_out, b"\x00")
                seq_out += 1
                break

            # 23. CharSaveReq (0x1438)
            elif msg_id == Msg.CS_IN_CHARSAVE_REQ:
                char_records = update_char_records(char_records, payload)
                get_player_store().save_characters(current_callsign, char_records)
                state.set_loadout(session_id, equip_records, char_records)
                if current_room:
                    publish_rooms()
                codec.write_frame(conn, Msg.CS_IN_CHARSAVE_ACK, seq_out, b"\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1439 (CharSaveAck)")

            # 24. EventListReq (0x1338)
            elif msg_id == Msg.CS_CH_EVENTLIST_REQ:
                codec.write_frame(conn, Msg.CS_CH_EVENTLIST_ACK, seq_out, build_event_list_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1339 (EventListAck)")

            # 25. Pride / Clan Changed Notification (0x1730)
            elif msg_id == Msg.CS_PR_CHANGED_REQ or msg_id == Msg.CS_PR_CHANGED_ACK:
                # Notification only - no reply expected
                pass

            # 26. PrideBattleResultReq (0x173C)
            elif msg_id == Msg.CS_PR_BATTLERESULT_REQ:
                codec.write_frame(conn, Msg.CS_PR_BATTLERESULT_ACK, seq_out, build_pride_battle_result_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x173D (PrideBattleResultAck)")

            # 27. PrideBattleListReq (0x173E)
            elif msg_id == Msg.CS_PR_BATTLELIST_REQ:
                codec.write_frame(conn, Msg.CS_PR_BATTLELIST_ACK, seq_out, b"\x00\x00\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x173F (PrideBattleListAck)")

            # 28. PrideMemberIDsReq (0x1740)
            elif msg_id == Msg.CS_PR_MEMBERIDS_REQ:
                codec.write_frame(conn, Msg.CS_PR_MEMBERIDS_ACK, seq_out, b"\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1741 (PrideMemberIDsAck)")

            # 29. MyPrideInfoReq (0x1742)
            elif msg_id == Msg.CS_PR_MYPRIDEINFO_REQ:
                codec.write_frame(conn, Msg.CS_PR_MYPRIDEINFO_ACK, seq_out, b"\x00\x00\x00\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1743 (MyPrideInfoAck)")

            # 30. MyPrideChainReq (0x1746)
            elif msg_id == Msg.CS_PR_MYPRIDECHAIN_REQ:
                codec.write_frame(conn, Msg.CS_PR_MYPRIDECHAIN_ACK, seq_out, b"\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1747 (MyPrideChainAck)")

            # 31. PrideChainListReq (0x1748)
            elif msg_id == Msg.CS_PR_CHAINLIST_REQ:
                codec.write_frame(conn, Msg.CS_PR_CHAINLIST_ACK, seq_out, b"\x00\x00")
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1749 (PrideChainListAck)")

            # 32. PrideRecentRankReq (0x174A)
            elif msg_id == Msg.CS_PR_RECENTRANK_REQ:
                codec.write_frame(conn, Msg.CS_PR_RECENTRANK_ACK, seq_out, b"\x00" * 37)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x174B (PrideRecentRankAck)")

            # 33. GiftItemCountReq (0x1A0E)
            elif msg_id == Msg.CS_GI_ITEM_COUNT_REQ:
                codec.write_frame(conn, Msg.CS_GI_ITEM_COUNT_ACK, seq_out, build_gift_item_count_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1A0F (GiftItemCountAck)")

            # 34. GiftPeriodEndReq (0x1A0C)
            elif msg_id == Msg.CS_GI_PERIOD_END_REQ:
                codec.write_frame(conn, Msg.CS_GI_PERIOD_END_ACK, seq_out, build_gift_period_end_ack())
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1A0D (GiftPeriodEndAck)")

            # 35. GiftItemListReq (0x1A02)
            elif msg_id == Msg.CS_GI_ITEM_LIST_REQ:
                codec.write_frame(conn, Msg.CS_GI_ITEM_LIST_ACK, seq_out, b"\x00" * 16)
                seq_out += 1
                log(f"[GameServer 40707] SENT 0x1A03 (GiftItemListAck)")

            # 36. Telemetry / Mission queries (0x1670)
            elif msg_id == 0x1670:
                pass

            else:
                log(f"[GameServer 40707] Ignored unhandled msg 0x{msg_id:04X} ({len(payload)}B) to preserve client stability")

    except Exception as e:
        log(f"[GameServer 40707] Connection closed: {e}\n{traceback.format_exc()}")
    finally:
        with users_lock:
            writers.pop(session_id, None)
            connected_users[:] = [u for u in connected_users if u["session_id"] != session_id]
        state.disconnect(session_id)
        conn.close()
        publish_rooms()


def start_game_server(port=40707):
    try:
        s = exclusive_socket(socket.SOCK_STREAM)
        s.bind((BIND_IP, port))
        s.listen(20)
        mark_ready("tcp", port)
        log(f"[+] Game Server active on TCP port {port}")
        while True:
            conn, addr = s.accept()
            if not allowed_peer(addr[0]):
                conn.close()
                continue
            threading.Thread(target=handle_game_client, args=(conn, addr), daemon=True).start()
    except Exception as e:
        log(f"[!] Game Server bind failed on port {port}: {e}")

# ----------------------------------------------------------------------
# UDP Match & Relay Sync (Ports 40707 & 40708)
# ----------------------------------------------------------------------
def parse_udp_registration(data, endpoint):
    if len(data) != 16 or data[:4] != b"\x02\x19\x0c\x00":
        return None
    if socket.inet_ntoa(data[10:14]) != endpoint[0] or \
       struct.unpack_from(">H", data, 14)[0] != endpoint[1]:
        return None
    return struct.unpack_from("<HI", data, 4)


def start_udp_listener(port):
    with exclusive_socket(socket.SOCK_DGRAM) as sock:
        sock.bind((BIND_IP, port))
        mark_ready("udp", port)
        log(f"[+] UDP listener active on {BIND_IP}:{port}; unverified endpoints are dropped")
        while True:
            data, endpoint = sock.recvfrom(65535)
            if not allowed_peer(endpoint[0]):
                continue
            if TRACE_DIR:
                with open(os.path.join(TRACE_DIR, "udp.jsonl"), "a", encoding="utf-8") as trace:
                    trace.write(json.dumps({"time": time.time(), "listener": port,
                                            "endpoint": endpoint, "payload": data.hex()}) + "\n")
            # Captured 2012 handshake: LE opcode 0x1902, LE body length 12,
            # LE session ID, LE nonce, IPv4 and BE advertised port.
            registration = parse_udp_registration(data, endpoint)
            if registration:
                sid, key = registration
                if state.identify_udp(sid, key, endpoint):
                    log(f"[UDP:{port}] Registered session {sid} at {endpoint}")
                continue
            if data == b"\xcc\xff":
                continue
            for peer in state.relay_targets(endpoint):
                sock.sendto(data, peer)


def start_dummy_listener(port=10300):
    """Auxiliary listener to accept and keep alive secondary game telemetry/billing connections."""
    try:
        s = exclusive_socket(socket.SOCK_STREAM)
        s.bind((BIND_IP, port))
        s.listen(5)
        mark_ready("tcp", port)
        log(f"[+] Dummy Service active on TCP port {port}")
        while True:
            conn, addr = s.accept()
            if not allowed_peer(addr[0]):
                conn.close()
                continue
            # Keep open
            def dummy_worker(c):
                try:
                    while True:
                        d = c.recv(1024)
                        if not d: break
                except:
                    pass
                finally:
                    try: c.close()
                    except: pass
            threading.Thread(target=dummy_worker, args=(conn,), daemon=True).start()
    except Exception as e:
        log(f"[!] Dummy listener warning on port {port}: {e}")

# ----------------------------------------------------------------------
# Unsafe absolute-address memory watcher removed.
# ----------------------------------------------------------------------
# Main Entry Point
# ----------------------------------------------------------------------
def main():
    print("=" * 60)
    print("      WolfTeam Localhost & LAN Server Emulator (V6)")
    print("=" * 60)
    print("[+] Initializing encrypted wire protocol & services...")


    # Start Port 80 HTTP
    threading.Thread(target=start_http_server, args=(80,), daemon=True).start()

    # Start Broker on 40706 and 30706
    threading.Thread(target=start_broker_server, args=(40706,), daemon=True).start()
    threading.Thread(target=start_broker_server, args=(30706,), daemon=True).start()

    # Start GameServer on 40707
    threading.Thread(target=start_game_server, args=(40707,), daemon=True).start()

    # Start Auxiliary service on 10300
    threading.Thread(target=start_dummy_listener, args=(10300,), daemon=True).start()

    # Start UDP Relay on 40707 and 40708
    threading.Thread(target=start_udp_listener, args=(40707,), daemon=True).start()
    threading.Thread(target=start_udp_listener, args=(40708,), daemon=True).start()

    required = {("tcp", port) for port in (80, 30706, 40706, 40707, 10300)}
    required.update({("udp", 40707), ("udp", 40708)})
    for _ in range(50):
        with ready_lock:
            missing = required - ready_services
        if not missing:
            break
        time.sleep(0.1)
    if missing:
        log(f"[!] Startup failed, missing services: {sorted(missing)}")
        return 1
    if TRACE_DIR:
        os.makedirs(TRACE_DIR, exist_ok=True)
        ready_path = os.path.join(TRACE_DIR, "ready.json")
        with open(ready_path + ".tmp", "w", encoding="utf-8") as f:
            json.dump({"pid": os.getpid(), "bind": BIND_IP, "services": sorted(required)}, f)
        os.replace(ready_path + ".tmp", ready_path)
    log("[+] All required TCP and UDP listeners are ready.")
    log("[+] Press Ctrl+C to terminate server.")

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("Server stopping...")

if __name__ == "__main__":
    sys.exit(main())

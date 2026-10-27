"""Thread-safe lobby state; UDP endpoints are never inferred from TCP ports."""
import copy
import secrets
import threading


class StateError(ValueError):
    pass


class LobbyState:
    def __init__(self):
        self.lock = threading.RLock()
        self.sessions = {}
        self.rooms = []
        self.next_id = 1

    def connect(self, ip):
        with self.lock:
            for _ in range(65535):
                sid = self.next_id
                self.next_id = sid % 65535 + 1
                if sid not in self.sessions:
                    self.sessions[sid] = {"ip": ip, "port": 0, "udp": None,
                                          "key": secrets.randbits(32), "room": None}
                    return sid
            raise StateError("Session capacity reached")

    def leave(self, sid):
        with self.lock:
            session = self.sessions[sid]
            room = session["room"]
            session["room"] = None
            if room is None:
                return
            room["users"][:] = [u for u in room["users"] if u["session_id"] != sid]
            if not room["users"]:
                self.rooms.remove(room)
            elif room["master_slot"] not in {u["slot"] for u in room["users"]}:
                room["master_slot"] = min(u["slot"] for u in room["users"])
                room["game_started"] = False

    def disconnect(self, sid):
        with self.lock:
            if sid in self.sessions:
                self.leave(sid)
                del self.sessions[sid]

    def create(self, sid, title):
        with self.lock:
            if self.sessions[sid]["room"] is not None:
                raise StateError("Already in a room")
            used = {r["number"] for r in self.rooms}
            number = next((n for n in range(1, 100) if n not in used), None)
            if number is None:
                raise StateError("Room capacity reached")
            room = {"number": number, "title": title[:40], "map": 1, "mode": 1,
                    "wins": 5, "time": 900, "points": 100, "capacity": 16,
                    "master_slot": 0, "game_started": False, "users": [], "power_master": 1}
            self.rooms.append(room)
            self.join(sid, number)
            return room

    def join(self, sid, number):
        with self.lock:
            session = self.sessions[sid]
            if session["room"] is not None:
                raise StateError("Already in a room")
            room = next((r for r in self.rooms if r["number"] == number), None)
            if room is None or room["game_started"] or len(room["users"]) >= room["capacity"]:
                raise StateError("Room unavailable")
            used = {u["slot"] for u in room["users"]}
            slot = next(s for s in range(room["capacity"]) if s not in used)
            name = session.get("callsign", "Player")
            room["users"].append({"login": name, "callsign": name, "session_id": sid,
                                  "slot": slot, "team": slot % 2 + 1, "team_slot": slot // 2,
                                  "ip": session["ip"], "port": session["port"], "room_status": 1,
                                  "equip_records": session.get("equip_records"),
                                  "char_records": session.get("char_records"),
                                  "status": 2 if slot == room.get("master_slot", 0) else 0})
            session["room"] = room
            return room

    def set_loadout(self, sid, equip_records, char_records):
        with self.lock:
            session = self.sessions[sid]
            session["equip_records"] = dict(equip_records)
            session["char_records"] = dict(char_records)
            if session["room"]:
                for member in session["room"]["users"]:
                    if member["session_id"] == sid:
                        member["equip_records"] = dict(equip_records)
                        member["char_records"] = dict(char_records)
                        break

    def snapshot(self):
        with self.lock:
            return copy.deepcopy(self.rooms)

    def identify_udp(self, sid, key, endpoint):
        """Register an endpoint after the 0x1902 packet validates SID and nonce."""
        with self.lock:
            session = self.sessions.get(sid)
            if not session or session["key"] != key or session["ip"] != endpoint[0]:
                return False
            if any(s["udp"] == endpoint for s in self.sessions.values() if s is not session):
                return False
            session["udp"] = endpoint
            session["port"] = endpoint[1]
            if session["room"]:
                for user in session["room"]["users"]:
                    if user["session_id"] == sid:
                        user["port"] = endpoint[1]
            return True

    def relay_targets(self, endpoint):
        with self.lock:
            sender = next((s for s in self.sessions.values() if s["udp"] == endpoint), None)
            if not sender or not sender["room"] or not sender["room"]["game_started"]:
                return []
            room = sender["room"]
            return [s["udp"] for s in self.sessions.values()
                    if s is not sender and s["room"] is room and s["udp"]]

"""Serialize the frame and sequence together for replies and broadcasts."""
import threading
from broker_codec import BrokerCodec


class ConnectionCodec(BrokerCodec):
    def __init__(self):
        super().__init__()
        self.send_lock = threading.Lock()
        self.sequence = 0
        self.trace = None

    def write_frame(self, sock, msg_id, seq=None, payload=b""):
        with self.send_lock:
            wire = super().write_frame(sock, msg_id, self.sequence, payload)
            if self.trace:
                self.trace(msg_id, self.sequence, payload)
            self.sequence = (self.sequence + 1) & 0xffff
            return wire

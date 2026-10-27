package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 * Minimal success acknowledgement for inventory actions whose client-side
 * state machine only requires the matching response message to continue.
 *
 * The request-specific item result is deliberately not fabricated here.  In
 * particular, an EX box must never be removed until its authoritative reward
 * response is known and committed atomically.
 */
public class InventoryActionAck extends ServerMsg {
    private final short msgId;

    public InventoryActionAck(short msgId) {
        this.msgId = msgId;
        size = 24;
        buffer = ByteBuffer.allocate(size);
    }

    public byte[] getData(short seqNum) {
        buffer.position(0);
        addByte((byte) new Random().nextInt());
        addShort(msgId);
        addShort(seqNum);
        addShort((short) ((size - 8) / 16));
        byte checksum = 0;
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);
        while (buffer.position() < buffer.capacity()) addByte((byte) 0);
        return buffer.array();
    }
}

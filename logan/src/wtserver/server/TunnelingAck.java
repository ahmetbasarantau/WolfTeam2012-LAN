package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 * CS_FD_TUNNELING_ACK (0x1679) - reply to CS_FD_TUNNELING_REQ (0x1678). At match-start the client sends
 * TUNNELING_REQ (UDP tunnel / NAT-traversal setup) and BLOCKS on this ACK; no reply = the "Network Error"
 * (3011/3114) disconnect.
 *
 * IMPORTANT: payload[0] is the STATUS/RESULT byte (0 = success), like every other match-start ack
 * (StartGameAck/EnterGameAck/InitFieldAck all send 0 there). The first version ECHOED the request payload,
 * which put the request's (nonzero) first byte at payload[0] -> the client read it as a rejection code and
 * threw "Access authority denied" (string 4001 / 0xFA1). So this now sends a clean header + zero payload =
 * status 0 = success. (If the client needs specific tunnel/peer data in the ACK, add it AFTER a 0 status
 * byte once Ghidra confirms the 0x1679 layout.)
 */
public class TunnelingAck extends ServerMsg {
    private final short msgId = (short) 0x1679;

    public TunnelingAck() {
        size = 24;
        buffer = ByteBuffer.allocate(size);
    }

    public byte [] getData(short seqNum) {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);                 // [0] random
        addShort(msgId);                 // [1:3] msgId (LE)
        addShort(seqNum);                // [3:5] seq (LE)
        short pSize = (short) ((size - 8) / 16);
        addShort(pSize);                 // [5:7] block count
        byte checksum = 0;
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);               // [7] checksum over [0..6]
        for (int i = buffer.position(); i < buffer.capacity(); i++) addByte((byte) 0); // payload[0]=0 (success) + pad
        return buffer.array();
    }
}

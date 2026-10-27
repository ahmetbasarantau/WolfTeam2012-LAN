package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.Client;
import wtserver.client.ServerMsg;

/**
 * Additive single-player seat (Sec 288/289). 0x1607 = CS_FD_ENTER_ACK -> FUN_342d7070 -> FUN_3418b9b0,
 * seats ONE slot WITHOUT the destructive 0x1605 room-wipe. Replicates the EXACT layout of the tree's
 * proven EnterAck 0x1607 builder - critically the RESULT byte at wire +8 MUST be 0 (nonzero -> pump
 * 4001 before the seat runs), then the B0 EC 8E 06 marker, then the per-player record. Parameterized for
 * a bot Client, fits the generic room broadcast. Full add: 0x1607 (seat) -> 0x1443 (body). Never 0x1605.
 */
public class EnterSeatAck extends ServerMsg {

    private final Client c;

    public EnterSeatAck(Client c) {
        this.c = c;
        buffer = ByteBuffer.allocate(512);
    }

    public byte[] getData(short seqNum) {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(ServerMsg.CS_FD_ENTER_ACK);   // msgId 0x1607
        addShort(seqNum);
        addShort((short) 0);
        byte checksum = 0;
        addByte(checksum);

        addByte((byte) EnterAck.ROOM_OK);      // [+8] RESULT = 0 (ROOM_OK) - MUST be 0 or 4001
        addByte((byte) 0xB0);                  // [+9..+12] fixed marker
        addByte((byte) 0xEC);
        addByte((byte) 0x8E);
        addByte((byte) 0x6);

        addShort((short) 0);
        addShort((short) c.getRoomSlot());
        addShort((short) 0);
        addByte((byte) 0);
        addByte((byte) c.getRoomTeam());
        addByte((byte) c.getRoomTeamSlot());
        addShort(c.getSessionId());
        addShort((short) 0);
        addByte((byte) c.getUserLevel());
        addInteger(0);
        addInteger(0);

        String id = c.getLoginName();
        int n = id.length();
        addByte((byte) n);
        addAsciiString(id);
        addByte((byte) (n * 2));
        addUnicodeString(id);
        addByte((byte) 0);                       // field 13: 3rd string (WIDE, empty) len=0
        // Sec 294 FINAL (Binary FUN_3418b9b0 field map): the connection block does NOT sit right after the
        // names (that was my bug - the client read charId bytes 0x7536=30006 as the endpoint, and the length
        // mismatch split the record into 2 entries = the duplicate). The additive parser reads the block AFTER
        // the skill/rune block + 2 count-lists + a 2-byte field + 3 status bytes. Zero every count so the
        // variable middle collapses and the block lands deterministically. ONE fix kills BOTH symptoms.
        addByte((byte) 0);                       // field 14: skillCount = 0  (parser clamps >3 -> 4; keep 0)
        addByte((byte) 0);                       // field 16: list1Count = 0
        addByte((byte) 0);                       // field 18: list2Count = 0
        addShort((short) 0);                     // field 20: 2-byte field
        addByte((byte) 0);                       // field 21: status -> +0xdc
        addByte((byte) 0);                       // field 22: status -> +0x32
        addByte((byte) 0);                       // field 23: status -> +0x4d
        // fields 24-27: CONNECTION BLOCK IP1·port1·IP2·port2 -> struct +0x6c/+0x9a/+0x94/+0x34, the endpoint
        // FUN_3415e120 formats + hands the engine. Bot = 127.0.0.1:40709 in both pairs (server bot-peer listener).
        // ATLAS: real peers get the RELAY endpoint (p2p routes through the server); bots keep their own.
        if (c.isBot()) {
            addInteger(c.getIp());                   // bot IP1 = 127.0.0.1
            addShort(htons(c.getUdpPort()));        // bot port1 = 40709
            addInteger(c.getUdpIp());                // bot IP2
            addShort(htons(c.getUdpPort()));        // bot port2
        } else {
            // Two address pairs per PEER_ENDPOINT_MODE (the engine tries BOTH): global (+0x6c/+0x9a)
            // then local (+0x94/+0x34). Centralized in GameServer.peerConnBlock so 0x1605/0x1607 match.
            int[] cb = (wtserver.GameServer.INSTANCE != null)
                     ? wtserver.GameServer.INSTANCE.peerConnBlock(c)
                     : new int[]{ wtserver.GameServer.RELAY_IP, wtserver.GameServer.RELAY_PORT,
                                  wtserver.GameServer.RELAY_IP, wtserver.GameServer.RELAY_PORT };
            addInteger(cb[0]); addShort(htons((short) cb[1]));   // IP1 global / public pair
            addInteger(cb[2]); addShort(htons((short) cb[3]));   // IP2 local  / direct pair
        }
        addByte((byte) 0);                       // field 28: trailerCount = 0 (record ends)

        size = (short) buffer.position();
        if ((size - 8) % 16 > 0) { size -= (size - 8) % 16; size += 16; }
        short pSize = (short) ((size - 8) / 16);
        buffer.position(5);
        addShort(pSize);
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);

        StringBuilder _hx = new StringBuilder();
        byte[] _arr = buffer.array();
        for (int _i = 0; _i < Math.min(size, 40); _i++) _hx.append(String.format("%02x ", _arr[_i] & 0xff));
        System.out.println("[ENTERSEAT 0x1607 HEX size=" + size + "] " + _hx.toString().trim());
        return buffer.array();
    }
}

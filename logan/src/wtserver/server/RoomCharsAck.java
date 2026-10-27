/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.Client;
import wtserver.GameServer;
import wtserver.client.ServerMsg;

/**
 *
 * @author MSI
 */
public class RoomCharsAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_FD_CHARS_ACK;
    
    public RoomCharsAck()
    {
        size = 8;
        buffer = ByteBuffer.allocate(8192);
    }
    

    // Character faction (red/blue) — chars are faction-locked at spawn.
    // Centralized in CharacterFaction.java: ONE truth shared with GameServer spawn logic.
    // Previously the two files had divergent lists (30002 was blue here, red there → 0-HP bug).
    private static boolean isRedFaction(int c)  { return CharacterFaction.isRedFaction(c); }
    private static boolean isBlueFaction(int c) { return CharacterFaction.isBlueFaction(c); }

    public byte [] getData(short seqNum, GameServer.Room room)
    {
        if (wtserver.ServerConfig.CLIENT_2012) {
            byte[] data=wtserver.Packet2012.roomRoster(seqNum,room);
            size=(short)data.length;
            return data;
        }
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        addShort((short)0);
        byte checksum = 0;
        addByte(checksum);

        addByte((byte) 0);
        addByte((byte) 1);
        addByte((byte) room.getRoomMaster());
        addShort(room.getRoomNumber());
        
        String title = room.getRoomTitle();
        addByte((byte) (title.length() * 2));
        addUnicodeString(title);
        addByte((byte) 0);
        addByte((byte) room.getMap()); //Map
        addInteger(room.getMode()); //Mode - full 4-byte u32 (preserves AI-Deathmatch flag 0x040000)
        addByte((byte) room.getWins());
        addByte((byte) 5);
        addByte((byte) 0);
        addByte((byte) 0);
        //addInteger(0x000005);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        addShort(room.getTime());
        addShort(room.getPoints());
        //addInteger(0x0064);
        addShort((short) 2);
        addShort((short) 0xC0);
        addByte((byte) room.getCapacity());
        addInteger(0);
        int nUsers = room.getNumberOfUsers();
        //addInteger(nUsers); // n Users
        addByte((byte) nUsers);
        System.out.println("nUsers " + nUsers);
        for(int i = 0; i < nUsers; i++)
        {
            Client c = room.getUsers().get(i);
            // SLOT FIX (Freshie + Binary): emit the ABSOLUTE slot (getRoomSlot), matching 0x1675 /
            // SpawnAck / ChangeSlotStatus / EnterGameAck. Was (byte)i = the COMPACTED getUsers() index,
            // which diverges from the abs slot for parity-seated bots with gaps (proven: host + 2 red bots
            // -> 2nd bot abs slot 3 but compacted index 2 -> "slot 2 lobby / slot 3 game" AND mis-color,
            // since lobby color = slot parity: abs 3=odd=red but compacted 2=even=blue). Real players are
            // contiguous so abs==compacted -> no change for them / real multiplayer unaffected. Emitting the
            // abs slot also RE-ALIGNS the roster object with the AI/spawn object onto the same slot, which
            // removes a confound before the scoreboard-double capture.
            addByte((byte) c.getRoomSlot());
            addInteger(0);
            // v2 per-faction routing: chars are FACTION-LOCKED at spawn (confirmed live - 30003 works
            // on red, 0-HP on blue). Send the player's RED char in the red (team-1) position and their
            // BLUE char in the blue (team-0) position, pulled from getCharSlots. Slot 1 (equipped) wins
            // for its faction. Fallback to the faction basic (30006 red / 30005 blue).
            // Use the same persisted selection as SpawnAck. Selecting a character
            // must not produce one model in the roster and another in-game.
            short _redChar = CharacterResolver.resolve(c.getUserId(), (byte) 2);
            short _blueChar = CharacterResolver.resolve(c.getUserId(), (byte) 1);
            System.out.println("[CHARLOAD] user=" + c.getUserId()
                    + " -> selected red=" + _redChar + " blue=" + _blueChar);
            // Instance 3: the ROSTER char block (not SpawnAck) is the color channel — it feeds the char
            // registry (FUN_3413c160) and character faction IS the color (real players: team1->30005 blue,
            // Sec 263). Force a bot's char HERE to test whether the roster char actually reaches the registry.
            // BOT_FORCE_CHAR=30005 -> put a blue char at BOTH faction positions. Outcomes (Instance 3):
            //   blue -> char drives color via roster (confirmed) -> fix = per-faction routing for bots.
            //   still red -> char isn't reaching the registry (Sec 258 block misalignment) -> diff the bytes.
            //   0 HP -> faction-lock rejected a blue char at the red position -> the bot reads the red slot.
            if (c.isBot() && wtserver.GameServer.BOT_FORCE_CHAR >= 0) {
                _redChar  = (short) wtserver.GameServer.BOT_FORCE_CHAR;
                _blueChar = (short) wtserver.GameServer.BOT_FORCE_CHAR;
                System.out.println("[AI] ROSTER char FORCED for bot '" + c.getLoginName() + "' -> " + _redChar
                                   + " at both positions (testing char-drives-color via the roster registry)");
            }
            addByte((byte) c.getRoomTeam()); //team
            addByte((byte) c.getRoomTeamSlot()); //unk
            if(c.getRoomSlotStatus() > 0)
                addByte((byte) 1); //slot status
            else addByte((byte) 0);
            //addShort((short) 0x00000831);
            addShort(c.getSessionId());
            addShort((short) 0);
            addByte((byte) c.getUserLevel()); //level
            addInteger(0);
            addInteger(0);
            String loginName = c.getLoginName();//"abez01";
            String callSign = c.getLoginName();
            System.out.println("callSign " + callSign + "c.getRoomSlotStatus() " + c.getRoomSlotStatus());
            addByte((byte) loginName.length());
            addAsciiString(loginName);
            addByte((byte) (callSign.length() * 2));
            addUnicodeString(callSign);
            // #10: client reads THREE strings per user (ASCII + WIDE + WIDE); this is the 3rd (WIDE,
            // empty = length 0). Was addShort(0) (2 bytes) -> 1 byte too many -> cursor drift (Ghidra).
            addByte((byte) 0);
            // ATLAS: real peers get the RELAY endpoint (p2p routes through the server); bots keep their own.
            if (c.isBot()) {
                addInteger(c.getIp());                      // bot global ip (127.0.0.1)
                addShort(htons(c.getUdpPort()));            // bot global port (40709)
                addInteger(c.getUdpIp());                   // bot local ip
                addShort(htons(c.getUdpPort()));            // bot local port
            } else {
                // Two address pairs per PEER_ENDPOINT_MODE (the engine tries BOTH): global (+0x6c/+0x9a)
                // then local (+0x94/+0x34). Centralized so 0x1605 and 0x1607 can't drift.
                int[] cb = (wtserver.GameServer.INSTANCE != null)
                         ? wtserver.GameServer.INSTANCE.peerConnBlock(c)
                         : new int[]{ wtserver.GameServer.RELAY_IP, wtserver.GameServer.RELAY_PORT,
                                      wtserver.GameServer.RELAY_IP, wtserver.GameServer.RELAY_PORT };
                addInteger(cb[0]); addShort(htons((short) cb[1]));   // global / public pair
                addInteger(cb[2]); addShort(htons((short) cb[3]));   // local  / direct pair
            }
            if(c.getRoomSlotStatus() == 0)
                addByte((byte) 1);
            else addByte(c.getRoomSlotStatus());
            // #16 stat count + #17 stat regions (Ghidra parse): count byte, then count x 33-byte
            // regions, each = [1 byte] + [15 x u16 = 30] + [2 bytes] = 33. Fixes the cursor drift that
            // made the client read char id 1 from the tail vector instead of 30005/30006.
            addByte((byte) 4);                                  // #16 stat count = 4
            for (int _s = 0; _s < 4; _s++) {                    // #17: 4 regions x 33 bytes
                addByte((byte) 0);
                for (int _k = 0; _k < 16; _k++) addShort((short) 0);
            }
            // (Removed addShort(0x7D1) marker: Ghidra's parse goes straight from the stat regions (#17)
            // to the team-1 char COUNT (#18). The extra 0x7D1 shifted the char read by 2 bytes so the
            // client read 0x0207 instead of 30006. Char count now lands right after the stat regions.)
            addByte((byte) 2);
            addShort(_redChar);  //team-1 (RED) char - player's red-faction char or red basic
            addShort(_redChar);
            addByte((byte) 2);
            addShort(_blueChar); //team-0 (BLUE) char - player's blue-faction char or blue basic
            addShort(_blueChar);
            addShort((short) 0);
            addByte((byte) 0x33);
            addByte((byte) 3);
            addShort((short) 0);
            addByte((byte) 0);
            addShort((short) 1);
            addByte((byte) 0);
            addShort((short) 2);
            addByte((byte) 0);
            //if (i + 1 < nUsers) {
            //}else addByte((byte) 0);
        }
            addByte((byte) 0x3D); //level
            addByte((byte) 0);
            addByte((byte) 0);
            addByte((byte) 0);
        /*
        byte unks[] = {0x00, 0x01, 0x00, 0x00, 0x00, 0x26, 0x4D, 0x00, 0x6F, 0x00, 0x76, 0x00, 0x65, 0x00, 0x20, 0x00, 0x69, 0x00, 0x74, 0x00, 0x20, 0x00, 0x6F, 0x00, 0x72, 0x00, 0x20, 0x00, 0x6C, 0x00, 0x6F, 0x00, 0x73, 0x00, 0x65, 0x00, 0x20, 0x00, 0x69, 0x00, 0x74, 0x00, 0x21, 0x00, 0x00, 0x07, 0x02, 0x02, 0x04, 0x00, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x58, 0x02, 0x3C, 0x00, 0x02, 0x00, (byte)0xC0, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x31, 0x08, 0x00, 0x00, 0x3D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x61, 0x66, 0x65, 0x7A, 0x30, 0x31, 0x12, 0x6A, 0x00, 0x68, 0x00, 0x67, 0x00, 0x36, 0x00, 0x79, 0x00, 0x65, 0x00, 0x67, 0x00, 0x66, 0x00, 0x64, 0x00, 0x00, 0x00, 0x56, 0x63, 0x0F, (byte)0x91, 0x70, 0x4D, (byte)0xC0, (byte)0xA8, 0x01, (byte)0x95, 0x70, 0x4D, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x02, 0x36, 0x75, 0x36, 0x75, 0x02, 0x35, 0x75, 0x35, 0x75, 0x00, 0x00};
        
        //byte unks[] = {0x00, 0x01, 0x00, 0x00, 0x00, 0x2E, 0x54, 0x00, 0x69, 0x00, 0x6D, 0x00, 0x65, 0x00, 0x20, 0x00, 0x74, 0x00, 0x6F, 0x00, 0x20, 0x00, 0x67, 0x00, 0x6F, 0x00, 0x20, 0x00, 0x77, 0x00, 0x6F, 0x00, 0x6C, 0x00, 0x66, 0x00, 0x20, 0x00, 0x68, 0x00, 0x75, 0x00, 0x6E, 0x00, 0x74, 0x00, 0x69, 0x00, 0x6E, 0x00, 0x67, 0x00, 0x00, 0x07, 0x02, 0x02, 0x04, 0x00, 0x01, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x58, 0x02, 0x3C, 0x00, 0x02, 0x00, (byte)0xC0, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x33, 0x08, 0x00, 0x00, 0x3D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06, 0x61, 0x66, 0x65, 0x7A, 0x30, 0x31, 0x12, 0x6A, 0x00, 0x68, 0x00, 0x67, 0x00, 0x36, 0x00, 0x79, 0x00, 0x65, 0x00, 0x67, 0x00, 0x66, 0x00, 0x64, 0x00, 0x00, 0x00, 0x56, 0x63, 0x0F, 0x18, 0x70, 0x4D, (byte)0xC0, (byte)0xA8, 0x01, (byte)0x95, 0x70, 0x4D, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte)0xD1, 0x07, 0x02, 0x36, 0x75, 0x36, 0x75, 0x02, 0x35, 0x75, 0x35, 0x75, 0x00, 0x00};
        for(int i = 0; i < unks.length; i++)
            addByte(unks[i]);
        addInteger(0);*/
        
        size = (short) buffer.position();
        if((size - 8) % 16 > 0)
        {
            size -= (size - 8) % 16;
            size += 16;
        }
        short pSize = (short) ((size - 8) / 16);
        buffer.position(5);
        addShort(pSize);
        for(int i = 0; i < 7; i++)
        {
            checksum += buffer.get(i);
        }
        addByte(checksum);
        // DIAG: dump the plaintext RoomCharsAck bytes (pre-encryption = what the client parses) so
        // Ghidra can diff byte-for-byte against the client parse map (FUN_3418f2c0) and pin the drift.
        {
            StringBuilder _hx = new StringBuilder();
            byte[] _arr = buffer.array();
            for (int _i = 0; _i < size; _i++) _hx.append(String.format("%02x ", _arr[_i] & 0xff));
            System.out.println("[CHARSACK HEX size=" + size + "] " + _hx.toString().trim());
        }
        return buffer.array();
    }
}

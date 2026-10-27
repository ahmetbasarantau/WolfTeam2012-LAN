/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.Client;
import wtserver.client.ServerMsg;
import wtserver.database.Users;

/**
 * CS_IN_EQUIP_ACK (0x1414/0x1415) - handles the client's equip-change request.
 *
 * BUG FIXED (the "all guns 0 0 0 0" issue): the old version wrote equipment using NAMED fields
 * (setEquip("primary_wp1", ...)). The PHP equip handler does intval($_GET['field']), and
 * intval("primary_wp1") == 0 (PHP stops at the first non-digit), so EVERY equip write - primary,
 * secondary, knife, all 4 weapon sets - collapsed into equipment slot 0, each clobbering the last.
 * Only slot 0 ended up set; EquipListAck then read slots [0],[6],[12],[18] and found only [0]
 * non-zero -> the client showed all-but-one weapon as empty/0.
 *
 * THE FIX: write to NUMBERED slots that match exactly what EquipListAck READS back, so the round
 * trip is consistent. EquipListAck reads, per weapon set i (i=0..3, wp=i*6):
 *     equipList[wp]   = primary     equipList[wp+2] = secondary    equipList[wp+4] = knife
 *     equipList[wp+5] = grenade1    equipList[wp+3] = grenade2
 *   powerups (set 0 only): equipList[24..28]
 * So we write each piece to that same slot number (passed to setEquip as a numeric string, which
 * PHP intval()s correctly into the slot column).
 *
 * Also fixed the copy-paste bug where power4 and power5 both wrote "powerup1".
 *
 * The value stored is the UNIQUE id the client sent (which equals the inventory Unique we handed
 * out in ItemListAck); EquipListAck sends it back and the client matches it to the owned item.
 *
 * @author MSI
 */
public class EquipAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_IN_EQUIP_ACK;

    public EquipAck(byte recv_buffer [], int usr_id)
    {
        size = 8;
        buffer = ByteBuffer.allocate(32);
        if (recv_buffer == null || recv_buffer.length < 1) {
            System.out.println("Equip ignored: empty payload");
            return;
        }
        int n = recv_buffer[0] & 0xff;
        Users users = new Users();
        for (java.util.Map.Entry<Integer, Integer> entry : Equipment2012.decode(recv_buffer).entrySet())
            users.setEquip(String.valueOf(entry.getKey()), entry.getValue(), usr_id);
    }

    public byte [] getData(short seqNum)
    {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        addShort((short)0);
        byte checksum = 0;
        addByte(checksum);

        addByte((byte) 0);

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
        return buffer.array();
    }
}

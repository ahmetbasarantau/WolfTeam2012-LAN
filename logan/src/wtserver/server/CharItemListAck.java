/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;
import wtserver.client.ServerMsg;
import wtserver.database.Users;

/**
 * CS_IN_CHARITEMLIST_ACK - owned characters AND their clothing (30001..34000).
 *
 * DB-DRIVEN (Step 2): previously this sent a HARDCODED 2-character byte template, so bought/seeded
 * characters duplicated or didn't show. Now it serves the real characters from the DB, filtered to
 * inventory.type == 1 (character). Weapons (type == 0) are served by ItemListAck.
 *
 * Wire format (matches the proven hardcoded template that worked):
 *   header (9 bytes): 00 01 00 00 00 [nChars] 00 00 00
 *   per character (25 bytes): unique(4 LE) + itemCode(2 LE) + 0x0E + date(14 ASCII) + FF FF + 00 00
 *
 * Unique = 2,000,000,000 + item code (same deterministic scheme as weapons), so it's stable.
 *
 * @author MSI
 */
public class CharItemListAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_IN_CHARITEMLIST_ACK;
    private int user_id = 0;

    // inventory.type value that marks a row as a CHARACTER (vs weapon/item).
    private static final int TYPE_CHARACTER = 1;

    public CharItemListAck(int user_id)
    {
        this.user_id = user_id;
        size = 8;
        buffer = ByteBuffer.allocate(32768);
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

        // Pull the player's inventory and keep only characters (type == 1).
        Users users = new Users();
        ArrayList<Users.Item> all = users.getItems(user_id);
        LinkedHashMap<Integer, Users.Item> charsByCode = new LinkedHashMap<Integer, Users.Item>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).type == TYPE_CHARACTER && !charsByCode.containsKey(all.get(i).id))
                charsByCode.put(all.get(i).id, all.get(i));
        }
        ArrayList<Users.Item> chars = new ArrayList<Users.Item>(charsByCode.values());
        int nChars = chars.size();
        boolean compactPermanentDates = 9 + 25 * nChars > wtserver.BrokerCrypto.MAX_PAYLOAD_BYTES;

        // Header: 00 01 00 00 00 [nChars] 00 00 00  (9 bytes)
        addByte((byte) 0);
        addInteger(1); // replace inventory
        addInteger(nChars); // native count is u32, including clothing entries
        size += 9;

        // Per-character entry (25 bytes each)
        for (int i = 0; i < chars.size(); i++) {
            size += 25;
            int code = chars.get(i).id;
            addInteger(2000000000 + code);   // unique (4)
            addShort((short) code);          // item code (2)
            String expiry = sanitizeExpiry(chars.get(i).expiry);
            String wireExpiry = compactPermanentDates && "20991231235959".equals(expiry) ? "" : expiry;
            addByte((byte) wireExpiry.length());
            addAsciiString(wireExpiry);
            addShort((short) 0xFFFF);        // trailing marker (matches working template)
            addShort((short) 0x0000);
        }

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

    /**
     * Normalize an expiry value into a 14-char "YYYYMMDDHHMMSS" string (the wire field is always 14
     * ASCII bytes). 'permanent' or any non-date value falls back to a fixed valid timestamp.
     */
    private static String sanitizeExpiry(String expiry)
    {
        if (expiry != null) {
            String cleaned = expiry.replace(" ", "").replace("-", "").replace(":", "");
            if (cleaned.length() == 14) {
                boolean allDigits = true;
                for (int i = 0; i < cleaned.length(); i++) {
                    if (!Character.isDigit(cleaned.charAt(i))) { allDigits = false; break; }
                }
                if (allDigits)
                    return cleaned;
            }
        }
        // A permanent character must not serialize as an already-expired 2014 item.
        return "20991231235959";
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import wtserver.client.ServerMsg;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;
import java.text.SimpleDateFormat;
import java.util.Date;
import wtserver.database.Users;

/**
 * CS_IN_ITEMLIST_ACK (0x1431) - the player's WEAPON/ITEM inventory.
 *
 * DB-DRIVEN (Step 2): previously this sent 4 HARDCODED starter weapons (3001/1001/2001/9001)
 * ALWAYS, then appended DB items. That hardcoded block caused: phantom weapons that survived DB
 * wipes, and double-show when a bought/seeded item matched a default. Now it serves ONLY real DB
 * rows, filtered to non-character items. Characters/clothing (type == 1) are served separately by
 * CharItemListAck. This includes power items even when they use a non-zero item category.
 * faking them here.
 *
 * The inventory.type column is the category discriminator: 0 = weapon, 1 = character.
 *
 * @author MSI
 */
public class ItemListAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_IN_ITEMLIST_ACK;
    private int user_id = 0;

    // Characters use their own wire record. All other inventory categories use this record.
    private static final int TYPE_CHARACTER = 1;

    public ItemListAck(int user_id)
    {
        this.user_id = user_id;
        size = 8;
        buffer = ByteBuffer.allocate(8192);
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

        // Characters are delivered by CharItemListAck. Filtering only type==0 hid power items
        // stored under a different category.
        Users users = new Users();
        ArrayList<Users.Item> all = users.getItems(user_id);
        LinkedHashMap<Integer, Users.Item> weaponsByCode = new LinkedHashMap<Integer, Users.Item>();
        LinkedHashMap<Integer, Integer> quantityByCode = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < all.size(); i++) {
            Users.Item item = all.get(i);
            if (item.type != TYPE_CHARACTER) {
                Users.Item previous = weaponsByCode.get(item.id);
                if (previous == null || isLaterExpiry(item.expiry, previous.expiry))
                    weaponsByCode.put(item.id, item);
                Integer old = quantityByCode.get(item.id);
                quantityByCode.put(item.id, old == null ? 1 : old + 1);
            }
        }

        ArrayList<Users.Item> weapons = new ArrayList<Users.Item>(weaponsByCode.values());
        int nItems = weapons.size();
        addByte((byte) nItems);
        size += 2;

        for (int i = 0; i < weapons.size(); i++) {
            size += 33;
            // Deterministic Unique id = 2,000,000,000 + item code (same scheme as equip, Fix A) so the
            // inventory Unique always reconciles with what the client equips and EquipListAck reads back.
            addInteger(2000000000 + weapons.get(i).id);
            addShort((short) weapons.get(i).id);
            addShort((short) 0);
            addShort((short) 0);
            addShort((short) 0);
            addShort((short) 0);
            addByte((byte) 0xE);
            // Expiry as a 14-char date string. For 'permanent' (or any non-date value) fall back to a
            // fixed valid timestamp so the field is always exactly 14 chars (the client reads 0xE bytes).
            String expiry = sanitizeExpiry(weapons.get(i).expiry);
            addAsciiString(expiry);
            // Timed records use this field as remaining days. Permanent
            // consumables use it as quantity. Sending one for every record
            // caused correctly stored 30/7-day purchases to display as 1 day.
            int wireRemaining = wireDurationOrQuantity(weapons.get(i).expiry,
                    quantityByCode.get(weapons.get(i).id));
            if (!"20991231235959".equals(sanitizeExpiry(weapons.get(i).expiry))) {
                System.out.println("[ITEMLIST] timed item=" + weapons.get(i).id
                        + " expiry=" + sanitizeExpiry(weapons.get(i).expiry)
                        + " remainingDays=" + wireRemaining);
            }
            addInteger(wireRemaining);
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
     * Normalize an expiry value into a 14-char "YYYYMMDDHHMMSS" string. The wire format always sends
     * exactly 14 ASCII bytes (length prefix 0xE). 'permanent' or any non-14-digit value falls back to
     * a fixed valid date so the field length stays correct.
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
        // A permanent item must not serialize as an already-expired 2015 item.
        return "20991231235959";
    }

    private static boolean isLaterExpiry(String candidate, String current)
    {
        return sanitizeExpiry(candidate).compareTo(sanitizeExpiry(current)) > 0;
    }

    private static int wireDurationOrQuantity(String expiry, int quantity)
    {
        String clean = sanitizeExpiry(expiry);
        if ("20991231235959".equals(clean)) return Math.max(1, quantity);
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
            df.setLenient(false);
            Date until = df.parse(clean);
            long remaining = until.getTime() - System.currentTimeMillis();
            if (remaining <= 0) return 0;
            long day = 24L * 60L * 60L * 1000L;
            return (int) Math.min(Integer.MAX_VALUE, (remaining + day - 1L) / day);
        } catch (Exception ignored) {
            return Math.max(1, quantity);
        }
    }
}

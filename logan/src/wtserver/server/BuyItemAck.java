/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;
import wtserver.database.Users;
import wtserver.database.Users.PurchaseResult;

/**
 *
 * @author MSI
 */
public class BuyItemAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_IN_BUYITEM_ACK;
    short itemId;
    int itemCode;
    byte days, purchaseCurrency;
    long price;
    String expiry = "20991231235959";
    boolean success = false;
    private int ownerId;
    
    public BuyItemAck(byte recv_buffer [], int user_id)
    {
        ownerId=user_id;
        size = 8;
        buffer = ByteBuffer.allocate(128);
        // The protocol transports a uint16 item code.  Keep its unsigned form
        // for persistence: casting first made every code >= 32768 (notably
        // characters) negative in MySQL.
        if (recv_buffer == null || recv_buffer.length < 10) {
            System.out.println("[BUY] rejected truncated request");
            return;
        }
        itemCode = (recv_buffer[0] & 0xff) + ((recv_buffer[1] & 0xff) * 0x100);
        itemId = (short) itemCode;
        days = (byte) (recv_buffer[2] & 0xff);
        // Logs prove this is the payment selector, not quantity:
        // AP products use 1, Gold products use 4. 2 is the protocol's WC bit.
        purchaseCurrency = (byte) (recv_buffer[5] & 0xff);
        // Price is u32 LE. The old signed short turned 49,000/500,000 into
        // negative values and made any reliable debit impossible.
        price = ((long) recv_buffer[6] & 0xffL)
                | (((long) recv_buffer[7] & 0xffL) << 8)
                | (((long) recv_buffer[8] & 0xffL) << 16)
                | (((long) recv_buffer[9] & 0xffL) << 24);
        // Native 34089510: characters and their clothing share the 0x1437 inventory.
        int itemType = itemCode >= 30001 && itemCode <= 34000 ? 1 : 0;
        Users users = new Users();
        PurchaseResult result = users.purchase(user_id, itemCode, itemType,
                days & 0xff, purchaseCurrency & 0xff, price);
        success = result.success;
        if (success) {
            expiry = result.expiry;
            if (isPowerSlotVoucher(itemCode) || isWeaponSlotVoucher(itemCode))
                users.syncSlotVoucherRows(user_id);
            System.out.println("[BUY] committed item=" + itemCode + " days=" + (days & 0xff)
                    + " currency=" + currencyName(purchaseCurrency & 0xff)
                    + " price=" + price + " balances=" + result.gold + "/" + result.wc + "/" + result.cash);
        } else {
            System.out.println("[BUY] rejected item=" + itemCode + " currency="
                    + (purchaseCurrency & 0xff) + " price=" + price + " reason=" + result.error);
        }
    }

    public byte [] getData(short seqNum, byte status)
    {
        if (wtserver.ServerConfig.CLIENT_2012) {
            byte[] data=wtserver.Packet2012.purchase(seqNum,itemCode,days&255,purchaseCurrency&255,
                          sanitizeExpiry(expiry),new Users().getUserInfo(ownerId),success);
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
        
        addByte((byte) status);
        addByte((byte) 0xFA);
        addByte((byte) 0);
        //TODO
        addByte((byte) 0xAA);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        
        addByte((byte) 0x11);
        addByte((byte) 0xB7);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        
        addInteger(2000000000 + itemCode);           // unique ID
        addShort(itemId);                             // item code
        addByte((byte) 0x0E);
        addAsciiString(sanitizeExpiry(expiry));       // actual expiry from committed DB row
        addByte(days);                                // days
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 2);
        addByte(purchaseCurrency);                    // purchase/payment selector echoed by client
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
        addByte((byte) 0);
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
    
    public long getPrice()
    {
        return price;
    }

    public boolean isSuccess()
    {
        return success;
    }

    private static String sanitizeExpiry(String value)
    {
        if (value != null && value.matches("[0-9]{14}")) return value;
        return "20991231235959";
    }

    private static String currencyName(int code)
    {
        if (code == 1) return "AP";
        if (code == 2) return "WC";
        if (code == 4) return "GOLD";
        return "UNKNOWN(" + code + ")";
    }

    private static boolean isPowerSlotVoucher(int code)
    {
        return code >= 10050 && code <= 10059;
    }

    private static boolean isWeaponSlotVoucher(int code)
    {
        return code >= 10061 && code <= 10064;
    }
}

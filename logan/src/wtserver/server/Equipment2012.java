package wtserver.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** 2012 CShell 341cc1f0 reader / 341093b0 writer. Keep DB and wire order together. */
public final class Equipment2012 {
    public static final int PRESETS = 4;
    public static final int POWER_BASE = 24;
    public static final int POWERS = 15;
    public static final int DB_SIZE = POWER_BASE + POWERS;
    public static final int RECORD_SIZE = 88;
    // DB: primary, extra primary, handgun, grenade2, knife, grenade1.
    // Native 341cc1f0 and the donor's original 0x1414 reader agree on these offsets.
    private static final int[] WEAPON_OFFSETS = {0, 5, 10, 19, 83, 15};

    private Equipment2012() { }

    public static byte[] record(int[] equipment, int preset, int powerExtensions) {
        ByteBuffer out = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < WEAPON_OFFSETS.length; i++)
            out.putInt(WEAPON_OFFSETS[i], equipment[preset * 6 + i]);
        if (preset == 0)
            for (int i = 0; i < POWERS; i++) out.putInt(23 + i * 4, equipment[POWER_BASE + i]);
        // This is a COUNT of extra power slots, not the extra-main weapon flag.
        // UI 3422ee31 locks positions >= 5 + this count (15 positions total).
        out.put(87, (byte) Math.max(0, Math.min(10, powerExtensions)));
        return out.array();
    }

    public static Map<Integer, Integer> decode(byte[] payload) {
        if (payload == null || payload.length < 1)
            throw new IllegalArgumentException("Empty equipment save");
        int count = payload[0] & 255;
        if (count > PRESETS || payload.length < 1 + count * (1 + RECORD_SIZE))
            throw new IllegalArgumentException("Truncated equipment save");
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        Map<Integer, Integer> entries = new LinkedHashMap<Integer, Integer>();
        boolean[] seen = new boolean[PRESETS];
        for (int n = 0; n < count; n++) {
            int offset = 1 + n * (1 + RECORD_SIZE);
            int preset = payload[offset] & 255;
            if (preset >= PRESETS || seen[preset])
                throw new IllegalArgumentException("Invalid or duplicate equipment preset");
            seen[preset] = true;
            int body = offset + 1;
            for (int i = 0; i < WEAPON_OFFSETS.length; i++)
                entries.put(preset * 6 + i, in.getInt(body + WEAPON_OFFSETS[i]));
            if (preset == 0)
                for (int i = 0; i < POWERS; i++) entries.put(POWER_BASE + i, in.getInt(body + 23 + i * 4));
        }
        return entries;
    }
}

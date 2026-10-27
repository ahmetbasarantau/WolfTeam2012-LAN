package wtserver.server;

import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;

/**
 * Centralized character faction (red/blue) lookup.
 * Characters are faction-locked at spawn — a red character cannot spawn on blue team
 * and vice versa (results in 0-HP / no-model bug).
 *
 * Faction data sourced from characterdata.dat (record +8: 0=Blue/team1, 1=Red/team2).
 * This SINGLE source of truth replaces the duplicated (and divergent) lists that were
 * previously in GameServer.java and RoomCharsAck.java.
 *
 * @author MSI
 */
public class CharacterFaction {

    /** Characters belonging to the RED faction (team 2). */
    public static final Set<Integer> RED_CHARS;

    /** Characters belonging to the BLUE faction (team 1). */
    public static final Set<Integer> BLUE_CHARS;

    static {
        // Exact 21-byte records from the installed 2012 characterdata.dat,
        // with the existing custom-skin compatibility IDs retained below.
        // ---- RED faction (team 2) ----
        // 30003 Adriana → model path "Adriana_red" ✓
        // 30004 Pedro    → model path "Pedro_red" ✓
        // 30006 Kırmızı Üniforma (William Kroll)
        // 30069, 30071 → custom chars, used alongside RED in logs (verify by testing)
        Set<Integer> red = new HashSet<>(Arrays.asList(
            30003, 30004, 30006, 30009, 30010,
            30013, 30014, 30017, 30018, 30019, 30021,
            30025, 30026, 30029, 30030, 30031, 30032, 30035, 30036,
            30039, 30040, 30044, 30045, 30046,
            30069, 30071
        ));

        // ---- BLUE faction (team 1) ----
        // 30002 Christopher → model path "Christopher_blue" ✓
        // 30005 Mavi Üniforma (Duane Eddy)
        // 30024 → test confirmed: RED team = 0 HP → BLUE faction ✓
        Set<Integer> blue = new HashSet<>(Arrays.asList(
            30001, 30002, 30005, 30007, 30008, 30011, 30012,
            30015, 30016, 30020, 30022,
            30023, 30024, 30027, 30028, 30033, 30034, 30037, 30038,
            30041, 30042, 30043, 30047, 30048
        ));

        RED_CHARS  = Collections.unmodifiableSet(red);
        BLUE_CHARS = Collections.unmodifiableSet(blue);
    }

    /** Returns true if the character ID belongs to the red faction. */
    public static boolean isRedFaction(int charId) {
        return RED_CHARS.contains(charId);
    }

    /** Returns true if the character ID belongs to the blue faction. */
    public static boolean isBlueFaction(int charId) {
        return BLUE_CHARS.contains(charId);
    }

    /**
     * Returns the default (basic) character for the given team.
     * Team 1 = BLUE  → 30005 (Duane Eddy)
     * Team 2 = RED   → 30006 (William Kroll)
     */
    public static short defaultCharForTeam(byte team) {
        return (team == 1) ? (short) 30005 : (short) 30006;
    }
}

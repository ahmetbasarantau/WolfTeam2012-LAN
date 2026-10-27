/*
 * LoginServer.java - wtserver login server on port 8444 (HANDSHAKE version).
 * -----------------------------------------------------------------------------
 * Implements the real multi-message login handshake (Ghidra-confirmed, PROJECT_LOG Sec 71),
 * replacing the old one-shot success ACK that landed as "wrong password".
 *
 * THE SEQUENCE (launcher <-> us, all simple 8-byte headers [size LE][0000][msgId LE][field]):
 *   1. RX 0x1310 (login)   -> TX 0x1312 result 0x00  = CHALLENGE (minimal; its job is just to
 *                                                       make the client send its sysinfo next)
 *   2. RX 0x4000 (sysinfo) -> TX 0x4001 result 0x00  = Reply_StartGame (value = channel id)
 *                                                       -> client LAUNCHES the game.
 *   (optional) we can also send 0x1312 result 0x10 ("enable PLAY" UI) - toggle SEND_R10.
 *
 * KEY (the big simplification): we do NOT mint a session token. The client carries its OWN
 * LoginID + token (from the user's login input) into the game launch. Our only job is to drive
 * this sequence and send the channel id on 0x4001. The broker later sees the client's own token
 * - and we own the broker, so we accept it.
 *
 * RESULT CODES TO AVOID (would break/abort): 0x90 (No DB Test -> _exit), 0x19/0x91 (wrong pass),
 * 0x60 (version), 0x9001 (set-game-id redirect). We only ever send 0x00 (and optionally 0x10).
 *
 * Verbose logging: every RX/TX is dumped + a STATE line, so you can see exactly which step the
 * handshake reaches. Lives in wtserver, started from WTServer.main alongside Broker/Game.
 * -----------------------------------------------------------------------------
 */
package wtserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class LoginServer {

    public final int port;

    // ---- tunables for live testing ----
    // The channel/server id sent in 0x4001 (the capture used 0x13). If the game rejects it,
    // try a value matching one of your DB channel ids.
    public static final int CHANNEL_ID = 0x13;
    // Also send the optional 0x1312 result 0x10 ("enable PLAY") after the challenge? Try false
    // first (the sequence may not need it); flip to true if the launcher waits for it.
    public static final boolean SEND_R10 = false;
    // If the challenge needs the WolfTeam config-gated payload (DAT_004d238c==2), set true to
    // append 16 bytes @+8 + 20 bytes @+0x18. Start false (minimal header-only challenge).
    public static final boolean CHALLENGE_PAYLOAD = false;

    // msgIds (launcher protocol, little-endian on the wire)
    private static final int MSG_LOGIN_REQ   = 0x1310; // client -> us (login)
    private static final int MSG_AUTH_ACK    = 0x1312; // us -> client (challenge / result)
    private static final int MSG_SYSINFO     = 0x4000; // client -> us (auto after challenge)
    private static final int MSG_STARTGAME   = 0x4001; // us -> client (Reply_StartGame -> launch)

    public LoginServer(int port) {
        this.port = port;
        Thread t = new Thread(this::startServer, "LoginServer-" + port);
        t.setDaemon(true);
        t.start();
    }

    private void startServer() {
        try {
            ServerSocket socket = new ServerSocket(port);
            System.out.println("Login Server Started on port " + port + " (handshake mode)");
            while (true) {
                Socket c = socket.accept();
                System.out.println("LOGIN: connect from " + c.getRemoteSocketAddress());
                Thread h = new Thread(() -> handle(c), "LoginConn");
                h.setDaemon(true);
                h.start();
            }
        } catch (IOException ex) {
            System.out.println("LoginServer error: " + ex);
        }
    }

    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[8192];
            int chunk = 0;
            while (true) {
                int n = in.read(buf);
                if (n < 0) { System.out.println("LOGIN: client closed"); break; }
                chunk++;
                int msgId = (n >= 6) ? le16(buf, 4) : -1;
                System.out.println("[LOGIN RX #" + chunk + "] " + n + " bytes, msgId=0x"
                        + Integer.toHexString(msgId));
                dump(buf, n);

                switch (msgId) {
                    case MSG_LOGIN_REQ: {
                        System.out.println("  STATE: got 0x1310 LOGIN -> sending 0x1312 r0 CHALLENGE");
                        byte[] ch = buildChallenge();
                        send(out, ch, "0x1312 r0 challenge");
                        if (SEND_R10) {
                            byte[] r10 = buildAuthResult(0x10);
                            send(out, r10, "0x1312 r0x10 enable-PLAY");
                        }
                        break;
                    }
                    case MSG_SYSINFO: {
                        System.out.println("  STATE: got 0x4000 SYSINFO -> sending 0x4001 Reply_StartGame (LAUNCH)");
                        byte[] sg = buildStartGame(CHANNEL_ID);
                        send(out, sg, "0x4001 Reply_StartGame value=0x" + Integer.toHexString(CHANNEL_ID));
                        System.out.println("  STATE: StartGame sent - the client should now LAUNCH the game.");
                        break;
                    }
                    default:
                        System.out.println("  STATE: unhandled msgId 0x" + Integer.toHexString(msgId)
                                + " (no reply). If the handshake stalls here, tell Binary.");
                }
            }
        } catch (IOException ex) {
            System.out.println("LOGIN: handler ended: " + ex);
        }
    }

    // ---- packet builders (simple 8-byte header: [size LE][0000][msgId LE][field LE]) ----

    /** 0x1312 result 0x00 challenge. Minimal = header only (size 8). Its job is to trigger the
     *  client's 0x4000 send. Optionally appends the config-gated WolfTeam payload. */
    private byte[] buildChallenge() {
        if (!CHALLENGE_PAYLOAD) {
            return header(MSG_AUTH_ACK, 0x00, 8); // header-only, size 8
        }
        // config-gated payload: 16 bytes @+8, 20 bytes @+0x18 (placeholder zeros for now).
        // Layout: [8 hdr][16 @+8][... to +0x18][20 @+0x18]. +0x18 = 24, so +8..+24 = 16 bytes
        // exactly, then 20 bytes -> total 8 + 16 + 20 = 44 = 0x2C.
        int total = 0x2C;
        byte[] p = header(MSG_AUTH_ACK, 0x00, total);
        // bytes already zero from header() allocation; placeholders. Real values <- capture.
        return p;
    }

    /** 0x1312 with an arbitrary result code (e.g. 0x10 enable-PLAY). Appends a couple non-zero
     *  bytes so the success handler's DAT_004d6530!=0 (status-string index) gate passes. */
    private byte[] buildAuthResult(int result) {
        int total = 10; // 8 header + 2 payload (non-empty so the !=0 gate passes)
        byte[] p = header(MSG_AUTH_ACK, result, total);
        p[8] = 0x01; p[9] = 0x00; // tiny non-zero payload
        return p;
    }

    /** 0x4001 Reply_StartGame: header + value(u32 LE) = channel id. 12 bytes. This triggers launch. */
    private byte[] buildStartGame(int channelId) {
        int total = 12; // 8 header + 4 value
        byte[] p = header(MSG_STARTGAME, 0x00, total);
        p[8]  = (byte) (channelId & 0xff);
        p[9]  = (byte) ((channelId >>> 8) & 0xff);
        p[10] = (byte) ((channelId >>> 16) & 0xff);
        p[11] = (byte) ((channelId >>> 24) & 0xff);
        return p;
    }

    /** Build an 8-byte header into a `total`-sized array: [size LE][0000][msgId LE][field LE]. */
    private byte[] header(int msgId, int field, int total) {
        byte[] p = new byte[total];
        p[0] = (byte) (total & 0xff);
        p[1] = (byte) ((total >>> 8) & 0xff);
        p[2] = 0; p[3] = 0;
        p[4] = (byte) (msgId & 0xff);
        p[5] = (byte) ((msgId >>> 8) & 0xff);
        p[6] = (byte) (field & 0xff);
        p[7] = (byte) ((field >>> 8) & 0xff);
        return p;
    }

    private void send(OutputStream out, byte[] data, String label) throws IOException {
        out.write(data); out.flush();
        System.out.println("[LOGIN TX] " + label + " (" + data.length + " bytes):");
        dump(data, data.length);
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static void dump(byte[] b, int n) {
        for (int i = 0; i < n; i += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (int j = i; j < i + 16 && j < n; j++) {
                hex.append(String.format("%02x ", b[j] & 0xff));
                char c = (char) (b[j] & 0xff);
                asc.append((c >= 32 && c < 127) ? c : '.');
            }
            System.out.printf("    %04x  %-48s %s%n", i, hex.toString(), asc.toString());
        }
    }
}

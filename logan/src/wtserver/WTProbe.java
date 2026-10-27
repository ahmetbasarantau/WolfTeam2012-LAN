/*
 * WTProbe - a standalone test-client harness for the WolfTeam private server.
 *
 * PURPOSE: skip the "reboot client -> navigate login->lobby->channel->inventory" tax. This connects
 * to the GameServer, logs in, and then fires whatever REQ packets you want, printing the DECRYPTED
 * ACK payloads. Testing an ack becomes: run this, read the output. No game client, no navigation.
 *
 * It REUSES BrokerCrypto (the same crypto the real server/client use) so it's byte-compatible - this
 * is not new reverse-engineering, just wiring the pieces we already cracked into a driver.
 *
 * BUILD (from the wtserver-master root, where BrokerCrypto.java lives):
 *   javac -cp bin -d bin WTProbe.java
 * RUN (server must be running):
 *   java -cp bin WTProbe
 *   java -cp bin WTProbe admin test123 127.0.0.1 40708
 *   java -cp bin WTProbe admin test123 127.0.0.1 40708 0x1430 0x1436 0x1432
 *     (the trailing args are extra REQ msgIds to fire after login, in order)
 *
 * NOTE: package-less on purpose (top-level) so it can be compiled/run easily alongside the server
 * classes. BrokerCrypto must be on the classpath (it's in bin/ after the server is built).
 */

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import wtserver.BrokerCrypto;

public class WTProbe {

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private int seq = 0;
    private final String host;
    private final int port;
    private DatagramSocket udp;   // real UDP socket so the bot can be a match peer
    private int udpKey = -1;      // user_id echoed into the UDP registration (from login ACK [3:7])

    public WTProbe(String host, int port) throws Exception {
        this.host = host; this.port = port;
        sock = new Socket(host, port);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
    }

    // ---- low-level frame send/recv, reusing BrokerCrypto (the real transport) ----

    /** Build a PLAINTEXT frame (8-byte header + payload), then encode+send it. */
    private void send(int msgId, byte[] payload) throws Exception {
        int total = 8 + payload.length;
        // pad payload region to a 16-byte multiple (AES block size), like the server acks do
        int padTo = payload.length;
        if (padTo % 16 != 0) padTo += 16 - (padTo % 16);
        byte[] plain = new byte[8 + padTo];
        // header: [0]=random, [1:2]=msgId LE, [3:4]=seq LE, [5:6]=?, [6]=blockCount, [7]=checksum
        plain[0] = (byte) (Math.random() * 256);
        plain[1] = (byte) (msgId & 0xff);
        plain[2] = (byte) ((msgId >> 8) & 0xff);
        plain[3] = (byte) (seq & 0xff);
        plain[4] = (byte) ((seq >> 8) & 0xff);
        plain[5] = 0;
        plain[6] = (byte) (padTo / 16);   // block count
        System.arraycopy(payload, 0, plain, 8, payload.length);
        // checksum = sum(bytes[0:7]) & 0xff at [7]
        int cks = 0;
        for (int i = 0; i < 7; i++) cks += plain[i] & 0xff;
        plain[7] = (byte) (cks & 0xff);

        byte[] wire = BrokerCrypto.encodeFrame(plain);
        out.write(wire);
        out.flush();
        seq++;
    }

    /** Read one frame; return {msgId, decryptedPayload} or null on EOF. Prints it. */
    private Object[] recv() throws Exception {
        byte[] hdrWire = new byte[8];
        try { in.readFully(hdrWire, 0, 8); }
        catch (Exception e) { return null; }
        byte[] hdr = BrokerCrypto.decodeHeader(hdrWire);
        int msgId = BrokerCrypto.msgId(hdr);
        int blocks = BrokerCrypto.payloadBlocks(hdr);
        byte[] payload = new byte[0];
        if (blocks > 0) {
            int n = blocks * 16;
            byte[] buf = new byte[n];
            in.readFully(buf, 0, n);
            payload = BrokerCrypto.decodePayload(buf, hdr);
        }
        System.out.println(String.format("  RX msgId=0x%04x (%d)  payload(%d)= %s",
                msgId, msgId, payload.length, hex(payload)));
        return new Object[]{ msgId, payload };
    }

    /** Fire a REQ and read ALL immediately-following ACK frames (until a short pause / EOF). */
    private void fireAndRead(int msgId, byte[] payload, String label) throws Exception {
        System.out.println("\n>> FIRE " + label + String.format(" (0x%04x)", msgId));
        send(msgId, payload);
        // read responses for a short window
        sock.setSoTimeout(400);
        try {
            while (true) {
                Object[] r = recv();
                if (r == null) break;
            }
        } catch (java.net.SocketTimeoutException te) {
            // no more frames right now - that's fine
        }
    }

    // ---- the login handshake (0x1300) so we reach the in-channel state ----

    /** Build the 0x1300 login payload: [b0][b1][len hash][hash][len name][name][len pass][pass]. */
    private static byte[] buildLoginPayload(String name, String pass) {
        String hash = "0000000000000000000000000000000000"; // 34-char dummy hash (unused by server)
        ByteBuffer b = ByteBuffer.allocate(256);
        b.put((byte) 0x79);          // [0] - matches the client's observed lead byte (value unused)
        b.put((byte) 0x01);          // [1]
        b.put((byte) hash.length()); // [2] = hash length
        for (char c : hash.toCharArray()) b.put((byte) c);
        b.put((byte) name.length());
        for (char c : name.toCharArray()) b.put((byte) c);
        b.put((byte) pass.length());
        for (char c : pass.toCharArray()) b.put((byte) c);
        byte[] p = new byte[b.position()];
        b.rewind(); b.get(p);
        return p;
    }

    /** Build a CS_FD_CREATE_REQ payload (create a room). Layout mirrors CreateAck's parser:
     *  [titleLen][title (wide)][pwLen][pw][map][mode][4 unk][wins][2][time u16][2][points][2][02 00 C0 00][capacity] */
    private static byte[] buildCreatePayload(String title) {
        ByteBuffer b = ByteBuffer.allocate(128);
        // title, "wide" (each char followed by 0x00) - the parser reads every other byte
        byte[] t = new byte[title.length() * 2];
        for (int i = 0; i < title.length(); i++) { t[i*2] = (byte) title.charAt(i); t[i*2+1] = 0; }
        b.put((byte) t.length);          // [0] title length in bytes
        b.put(t);                        // [1..] title (18 bytes for "ProbeRoom")
        b.put((byte) 0);                 // [1+titleLen] password length = 0
        // CreateAck's parser reads the fields at FIXED indices after title+pw:
        //   index=20: map        (+1)
        //   [21]:     mode        (+4 -> skips 22,23,24)
        //   [25]:     wins        (+2 -> skips 26)
        //   [27,28]:  time u16     (+2)
        //   [29]:     points       (+2 -> skips 30; then +4 for the '02 00 C0 00' block at 31..34)
        //   [35]:     capacity      <-- MUST land here (was mis-placed before -> room showed "1/0")
        // So indices 20..35 = exactly these 16 bytes:
        byte[] tail = {
            0x01,             // [20] map = 1
            0x00,             // [21] mode = 0
            0x00, 0x00, 0x00, // [22..24] unk
            0x03,             // [25] wins = 3
            0x00,             // [26] pad
            0x05, 0x00,       // [27,28] time = 5
            0x64,             // [29] points = 100
            0x00,             // [30] pad
            0x02, 0x00, (byte)0xC0, 0x00, // [31..34] the '02 00 C0 00' block
            0x10              // [35] capacity = 16  (fixes the "1/0" -> "1/16")
        };
        b.put(tail);
        byte[] p = new byte[b.position()];
        b.rewind(); b.get(p);
        return p;
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x ", x & 0xff));
        return s.toString().trim();
    }

    private static byte[] empty16() { return new byte[16]; }

    public void close() throws Exception { sock.close(); }

    // ---- main ----

    /** Login (0x1300) and capture the udpKey (= user_id) from the 0x1301 ACK payload[3:7]. */
    private void loginAndCapture(String name, String pass) throws Exception {
        System.out.println("\n>> FIRE CS_CH_LOGIN_REQ (0x1300)");
        send(0x1300, buildLoginPayload(name, pass));
        sock.setSoTimeout(1500);
        try {
            while (true) {
                Object[] r = recv();
                if (r == null) break;
                int mid = (Integer) r[0];
                byte[] pay = (byte[]) r[1];
                if (mid == 0x1301 && pay.length >= 7) {
                    udpKey = (pay[3] & 0xff) | ((pay[4] & 0xff) << 8) | ((pay[5] & 0xff) << 16) | ((pay[6] & 0xff) << 24);
                    System.out.println("[UDP] captured udpKey (user_id) from login ACK = " + udpKey);
                }
            }
        } catch (java.net.SocketTimeoutException te) { }
    }

    /** Open a real UDP socket, register our addr with the server (type-2 packet), and echo peer probes
     *  so the other player's client can establish the match UDP link (WolfTeam does match data over UDP). */
    private void startUdpPeer() throws Exception {
        udp = new DatagramSocket();                 // ephemeral local UDP port on 127.0.0.1
        final int udpPort = udp.getLocalPort();
        System.out.println("[UDP] peer socket bound on 127.0.0.1:" + udpPort + " (udpKey=" + udpKey + ")");
        final InetAddress srv = InetAddress.getByName(host);
        // type-2 registration: [0]=2, [6..9]=udpKey(user_id LE), [10..13]=udpIp(127.0.0.1 LE), [14..15]=udpPort(LE)
        final byte[] reg = new byte[16];
        reg[0] = 2;
        reg[6] = (byte)(udpKey & 0xff);       reg[7] = (byte)((udpKey >> 8) & 0xff);
        reg[8] = (byte)((udpKey >> 16) & 0xff); reg[9] = (byte)((udpKey >> 24) & 0xff);
        reg[10] = 1; reg[11] = 0; reg[12] = 0; reg[13] = (byte)127;   // 127.0.0.1 (LE as the server reads it)
        reg[14] = (byte)(udpPort & 0xff); reg[15] = (byte)((udpPort >> 8) & 0xff);
        udp.send(new DatagramPacket(reg, reg.length, srv, port));
        System.out.println("[UDP] sent type-2 registration to " + host + ":" + port);
        // echo thread: reply to anything the peer/server sends (peer connectivity probe, 0xCC, etc.)
        Thread rx = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (true) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    udp.receive(in);
                    int n = in.getLength();
                    StringBuilder h = new StringBuilder();
                    for (int i = 0; i < Math.min(n, 24); i++) h.append(String.format("%02x ", buf[i] & 0xff));
                    System.out.println("[UDP] RX " + n + "B from " + in.getAddress().getHostAddress()
                                       + ":" + in.getPort() + " : " + h.toString().trim());
                    // NO echo-back. The bot must NOT reflect what it receives: Ghidra confirmed the client's
                    // match UDP is client<->SERVER, not peer-to-peer, so echoing was aimed at the wrong target.
                    // With the server now (correctly) echoing our type-2, a bot echo-back created an infinite
                    // server<->bot ping-pong storm. We just receive + log to prove the reply lands.
                } catch (Exception e) { System.out.println("[UDP] recv stopped: " + e); break; }
            }
        });
        rx.setDaemon(true); rx.start();
        // periodically re-register so the server keeps our UDP addr fresh through match-start
        Thread keep = new Thread(() -> {
            while (true) {
                try { Thread.sleep(4000); udp.send(new DatagramPacket(reg, reg.length, srv, port)); }
                catch (Exception e) { break; }
            }
        });
        keep.setDaemon(true); keep.start();
    }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "admin";
        String pass = args.length > 1 ? args[1] : "test123";
        String host = args.length > 2 ? args[2] : "127.0.0.1";
        int    port = args.length > 3 ? Integer.parseInt(args[3]) : 40707;

        // "lobby" -> stay in the lobby; "makeroom" -> create+leave; "joinroom N" -> join room N and stay.
        boolean lobbyMode = false;
        boolean makeRoom = false;
        int joinRoomNum = -1;   // >=0 means join that room number and hold
        int matchRoomNum = -1;  // >=0 means join that room, READY up, and hold (for a live 2-player match)
        boolean hostMode = false;  // create a room + auto-START (retry) so YOU join from the client
        List<Integer> extra = new ArrayList<Integer>();
        for (int i = 4; i < args.length; i++) {
            String a = args[i].toLowerCase();
            if (a.equals("lobby") || a.equals("--lobby")) { lobbyMode = true; continue; }
            if (a.equals("makeroom") || a.equals("--makeroom")) { makeRoom = true; continue; }
            if (a.equals("host") || a.equals("--host")) { hostMode = true; continue; }
            if (a.equals("match") || a.equals("--match")) {
                // next arg = room number: join it, ready up, and hold so the host can START a match
                if (i + 1 < args.length) { matchRoomNum = Integer.parseInt(args[i+1]); i++; }
                continue;
            }
            if (a.equals("joinroom") || a.equals("--joinroom")) {
                // the next arg is the room number
                if (i + 1 < args.length) { joinRoomNum = Integer.parseInt(args[i+1]); i++; }
                continue;
            }
            extra.add(a.startsWith("0x") ? Integer.parseInt(a.substring(2), 16) : Integer.parseInt(a));
        }

        String tag = (lobbyMode ? "  [LOBBY MODE]" : "") + (makeRoom ? "  [MAKEROOM]" : "")
                   + (joinRoomNum >= 0 ? "  [JOINROOM " + joinRoomNum + "]" : "")
                   + (matchRoomNum >= 0 ? "  [MATCH " + matchRoomNum + "]" : "")
                   + (hostMode ? "  [HOST]" : "");
        System.out.println("WTProbe -> " + host + ":" + port + " as " + name + tag);
        WTProbe p = new WTProbe(host, port);

        // 1) login (0x1300)
        p.loginAndCapture(name, pass);

        // 2) the inventory/char screen sequence a real client fires after login
        int[] defaultSeq = extra.isEmpty()
                ? new int[]{ 0x1334, 0x1430, 0x1432, 0x1436, 0x1434 } // userinfo, itemlist, equiplist, charitemlist, charinfo
                : new int[0];
        for (int m : defaultSeq) p.fireAndRead(m, empty16(), reqName(m));
        for (int m : extra)      p.fireAndRead(m, empty16(), reqName(m));

        // UDP peer: needed for a real match (host/match modes) so the other player can reach us over UDP
        if ((hostMode || matchRoomNum >= 0) && p.udpKey >= 0) {
            try { p.startUdpPeer(); } catch (Exception e) { System.out.println("[UDP] peer init failed: " + e); }
        }

        if (hostMode) {
            // HOST MODE: ProbeBot CREATES 'ProbeRoom', readies up, and tries START until YOU (joined from
            // your client + READY) make the ready-check pass. It STOPS sending START the moment the match
            // launches (StartGameAck payload[0]==0), so it doesn't re-broadcast start over and over (which
            // was confusing the real client into leaving). The host's START self-readies it; the JOINER
            // (you) must hit READY in the client (sends UDPREADY) or START keeps returning ATLEAST_2_READY.
            p.fireAndRead(0x1520, empty16(), "CS_CN_ADVERTALL_REQ");   // register in channel
            p.fireAndRead(0x1600, empty16(), "CS_FD_FIELDLIST_REQ");   // room list
            System.out.println("\n[HOST] creating room 'ProbeRoom'...");
            p.fireAndRead(0x160c, buildCreatePayload("ProbeRoom"), "CS_FD_CREATE_REQ"); // 5644 = 0x160c
            byte[] readyPayload = new byte[16]; readyPayload[0] = 1;   // ready = status 1
            p.fireAndRead(0x163c, readyPayload, "CS_FD_UDPREADY_REQ"); // 5692 - ProbeBot readies its team
            System.out.println("\n[HOST] 'ProbeRoom' created + ProbeBot READY.");
            System.out.println("[HOST] >>> In your client: JOIN 'ProbeRoom' and hit READY. <<<");
            System.out.println("[HOST] Trying START every 3s until you're joined + ready (stops on launch)...\n");
            boolean started = false;
            while (!started) {
                Thread.sleep(3000);
                try {
                    p.send(0x1618, empty16());        // STARTGAME_REQ
                    p.sock.setSoTimeout(1500);
                    try {
                        while (true) {
                            Object[] r = p.recv();
                            if (r == null) break;
                            int mid = (Integer) r[0];
                            byte[] pay = (byte[]) r[1];
                            if (mid == 0x1619 && pay.length > 0) {   // StartGameAck
                                if ((pay[0] & 0xff) == 0x00) {
                                    started = true;
                                    System.out.println("[HOST] *** MATCH STARTED *** ProbeBot holding (no more START).");
                                } else {
                                    System.out.println("[HOST] not ready yet (StartGameAck 0x"
                                        + Integer.toHexString(pay[0] & 0xff) + ") - join 'ProbeRoom' + hit READY.");
                                }
                                break;
                            }
                        }
                    } catch (java.net.SocketTimeoutException te) { /* no ack this round */ }
                } catch (Exception e) {
                    System.out.println("[HOST] connection dropped: " + e.getMessage());
                    break;
                }
            }
            // match launched: hold WITHOUT sending START again
            while (true) {
                Thread.sleep(4000);
                try { p.send(0x1202, empty16()); }
                catch (Exception e) { System.out.println("[HOST] connection dropped: " + e.getMessage()); break; }
            }
        } else if (matchRoomNum >= 0) {
            // MATCH MODE: enter channel, JOIN room <matchRoomNum>, then READY UP and hold. addUser puts
            // the bot on the emptier team (opposite the host), and the ready sets its slot status > 0 so
            // getTeamReadyCount() counts it -> the host can hit START (needs both teams present + ready).
            p.fireAndRead(0x1520, empty16(), "CS_CN_ADVERTALL_REQ");   // register in channel
            p.fireAndRead(0x1600, empty16(), "CS_FD_FIELDLIST_REQ");   // room list
            byte[] enterPayload = new byte[16];
            enterPayload[0] = (byte) (matchRoomNum & 0xff);
            enterPayload[1] = (byte) ((matchRoomNum >> 8) & 0xff);
            System.out.println("\n[MATCH] joining room " + matchRoomNum + " as ProbeBot...");
            p.fireAndRead(0x1606, enterPayload, "CS_FD_ENTER_REQ");    // 5638 = 0x1606
            // ready up: CS_FD_UDPREADY_REQ (5692 = 0x163C); payload[0] becomes the slot status (>0 = ready)
            Thread.sleep(500);
            byte[] readyPayload = new byte[16];
            readyPayload[0] = 1;   // ready
            System.out.println("[MATCH] readying up (CS_FD_UDPREADY_REQ)...");
            p.fireAndRead(0x163c, readyPayload, "CS_FD_UDPREADY_REQ"); // 5692 = 0x163C
            System.out.println("[MATCH] ProbeBot is IN room " + matchRoomNum + " on the opposite team and READY.");
            System.out.println("You (host) can now hit START. Holding - Ctrl+C to leave.\n");
            while (true) {
                Thread.sleep(4000);
                try {
                    p.send(0x1202, empty16());   // heartbeat to hold the connection
                    System.out.println("[MATCH] heartbeat sent (ProbeBot ready in room " + matchRoomNum + ")");
                } catch (Exception e) {
                    System.out.println("[MATCH] connection dropped: " + e.getMessage());
                    break;
                }
            }
        } else if (joinRoomNum >= 0) {
            // Enter the channel, then JOIN room <joinRoomNum> and HOLD (so a real client in that room
            // sees ProbeBot sitting there = 2 players in one room). The ENTER request is just the room
            // number as a u16 at bytes [0:2].
            p.fireAndRead(0x1520, empty16(), "CS_CN_ADVERTALL_REQ");   // register in channel
            p.fireAndRead(0x1600, empty16(), "CS_FD_FIELDLIST_REQ");   // room list
            byte[] enterPayload = new byte[16];
            enterPayload[0] = (byte) (joinRoomNum & 0xff);
            enterPayload[1] = (byte) ((joinRoomNum >> 8) & 0xff);
            System.out.println("\n[JOINROOM] joining room " + joinRoomNum + "...");
            p.fireAndRead(0x1606, enterPayload, "CS_FD_ENTER_REQ");    // 5638 = 0x1606
            System.out.println("[JOINROOM] joined (if the room exists). Holding - ProbeBot is now IN the room.");
            System.out.println("Your real client in room " + joinRoomNum + " should see ProbeBot. Ctrl+C to leave.\n");
            while (true) {
                Thread.sleep(4000);
                try {
                    p.send(0x1202, empty16());   // heartbeat to hold the connection
                    System.out.println("[JOINROOM] heartbeat sent (ProbeBot still in room " + joinRoomNum + ")");
                } catch (Exception e) {
                    System.out.println("[JOINROOM] connection dropped: " + e.getMessage());
                    break;
                }
            }
        } else if (makeRoom) {
            // Enter the channel, CREATE a room, hold briefly, then LEAVE it. This reproduces the
            // create-and-leave path on demand (where one of the bugs lives). Watch the SERVER log for
            // what fires on create + on exit.
            p.fireAndRead(0x1520, empty16(), "CS_CN_ADVERTALL_REQ");   // register in channel
            p.fireAndRead(0x1600, empty16(), "CS_FD_FIELDLIST_REQ");   // room list
            System.out.println("\n[MAKEROOM] creating a room...");
            p.fireAndRead(0x160c, buildCreatePayload("ProbeRoom"), "CS_FD_CREATE_REQ"); // 5644 = 0x160c
            System.out.println("[MAKEROOM] created. Holding 3s, then leaving...");
            Thread.sleep(3000);
            System.out.println("[MAKEROOM] leaving the room (CS_FD_EXIT_REQ)...");
            p.fireAndRead(0x160a, empty16(), "CS_FD_EXIT_REQ");        // 5642 = 0x160a
            System.out.println("[MAKEROOM] done - check the server log for the create/leave sequence.");
        } else if (lobbyMode) {
            // Enter the channel so the server lists ProbeBot in getChannelUsers() (roomNumber == -1),
            // then HOLD the connection open with periodic heartbeats. As long as this runs, ProbeBot
            // sits in the lobby and a real client in the same channel sees it in the player list.
            p.fireAndRead(0x1520, empty16(), "CS_CN_ADVERTALL_REQ"); // 5408 - registers ProbeBot in channel view
            p.fireAndRead(0x1600, empty16(), "CS_FD_FIELDLIST_REQ"); // 5632 - the room list
            System.out.println("\n[LOBBY MODE] ProbeBot is holding the connection open. It should now appear");
            System.out.println("in the channel player list. Press Ctrl+C to disconnect.\n");
            // Keep-alive loop: send a heartbeat (0x1202) periodically so the socket stays open.
            while (true) {
                Thread.sleep(4000);
                try {
                    p.send(0x1202, empty16());   // heartbeat; server handles as no-op
                    System.out.println("[LOBBY MODE] heartbeat sent (ProbeBot still in lobby)");
                } catch (Exception e) {
                    System.out.println("[LOBBY MODE] connection dropped: " + e.getMessage());
                    break;
                }
            }
        }

        System.out.println("\nDone. (Adjust the fired msgIds via command-line args.)");
        p.close();
    }

    private static String reqName(int m) {
        switch (m) {
            case 0x1300: return "CS_CH_LOGIN_REQ";
            case 0x1334: return "CS_CH_USERINFO_REQ";
            case 0x1430: return "CS_IN_ITEMLIST_REQ";
            case 0x1432: return "CS_IN_EQUIPLIST_REQ";
            case 0x1434: return "CS_IN_CHARINFO_REQ";
            case 0x1436: return "CS_IN_CHARITEMLIST_REQ";
            case 0x1438: return "CS_IN_CHARSAVE_REQ";
            default:     return String.format("REQ_0x%04x", m);
        }
    }
}

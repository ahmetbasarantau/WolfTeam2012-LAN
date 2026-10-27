/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import wtserver.client.ClientMsg;
import wtserver.client.ServerMsg;

/**
 * Broker per-connection reader/writer - CRYPTO transport version.
 *
 * The real client ENCRYPTS the broker connection:
 *   header(8B): whiten + modified-Blowfish-ECB (key "WolfTeam")
 *   payload:    AES-128-ECB, [6]*16 bytes
 * So we DECODE on read (header -> real msgId/seq/blockCount, then decrypt payload) and ENCODE
 * on write (ServerMsg builds a plaintext frame; we whiten+BF the header and AES the payload).
 * BrokerCrypto provides decodeHeader/msgId/seq/payloadBlocks/checksumOk/decodePayload/encodeFrame.
 *
 * Also: survives bad/oversized frames (logs + resyncs instead of crashing) and logs every
 * RX/TX so the broker traffic is visible.
 *
 * @author MSI
 */
public class Client {
    private Server server;
    private Socket socket;
    private byte recv_buffer[] = new byte [8192];
    private byte send_buffer[] = new byte [8192];
    private byte session_key[] = new byte [6];//{0x31, 0x08, 0x07, (byte)0xF0, 0x18, 0x04};//new byte [6];
    DataInputStream in = null;
    DataOutputStream out = null;
    short seqNum = 0;
    int udpIp = 0;
    short udpPort = 0;
    int relayPort = 0;   // per-player relay port (fixes N>=3 conn-block endpoint collision); 0 = unassigned
    int Ip = 0;
    short Port = 0;
    int advertisedIp = wtserver.ServerConfig.ADVERTISED_IP;   // the LOCAL interface this client connected on -> what we hand back so it can reach us (127.0.0.1 for local, Hamachi IP for remote). Falls back to the config constant (bots / wildcard).
    short roomNumber = -1, roomSlot = -1;
    byte roomTeam = -1, roomTeamSlot = -1, roomSlotStatus = 0;
    byte userLevel = 0;
    int user_id = 0;
    private boolean udpKicked = false;   // one-shot: have we sent the 0x130D UDP-bootstrap kick on this conn?
    private boolean isBot = false;        // AI-bot roster stand-in (no socket, no networking)
    // The 2012 client emits the chosen red/blue cards in 0x1438, then follows
    // with 0x143A selector bytes that can refer to stale UI indices.
    private int[] pendingCharacterLoadout;

    private String loginName, callSign;

    public Client(Server server, Socket socket)
    {
        Random ranGen = new SecureRandom();
        ranGen.nextBytes(session_key);
        session_key[1] = 0x08;
        session_key[2] = 0x07;
        session_key[3] = (byte)0xF0;
        session_key[4] = 0x18;
        session_key[5] = 0x04;

        byte ip [] = socket.getInetAddress().getAddress();
        Ip = (ip[0] & 0xff) + (ip[1] & 0xff)*0x100 + (ip[2] & 0xff)*0x10000 + (ip[3] & 0xff)*0x1000000;
        Port = (short) socket.getPort();
        // The interface THIS client reached us on = what we advertise back. Local clients get 127.0.0.1;
        // remote clients get the reachable interface. BUT: a remote player reaching us through a netsh
        // portproxy makes socket.getLocalAddress() resolve to 127.0.0.1 - advertising that back tells their
        // client "you're at 127.0.0.1" -> it dials its OWN loopback -> disconnects at the channel list. So:
        // if the REMOTE address is non-loopback (a real remote client) but the LOCAL interface came back
        // loopback, advertise the configured PUBLIC_IP (Hamachi) instead. Genuinely-local clients (remote
        // addr also loopback) keep 127.0.0.1.
        try {
            byte lip [] = socket.getLocalAddress().getAddress();
            byte rip [] = socket.getInetAddress().getAddress();
            boolean remoteIsLoopback = (rip.length == 4 && (rip[0] & 0xff) == 127);
            if (lip.length == 4) {
                int v = (lip[0] & 0xff) + (lip[1] & 0xff)*0x100 + (lip[2] & 0xff)*0x10000 + (lip[3] & 0xff)*0x1000000;
                boolean localIsLoopback = ((lip[0] & 0xff) == 127);
                if (v != 0 && !localIsLoopback) {
                    advertisedIp = v;                                   // server saw a real interface (Hamachi/LAN) -> use it
                } else if (!remoteIsLoopback) {
                    advertisedIp = wtserver.ServerConfig.PUBLIC_IP;     // remote client but local resolved loopback (portproxy) -> hand them the reachable IP
                    System.out.println("[ADV] remote client " + socket.getInetAddress().getHostAddress()
                            + " via loopback interface -> advertising PUBLIC_IP " + wtserver.ServerConfig.publicIpStr());
                } else if (v != 0) {
                    advertisedIp = v;                                   // genuinely local (both loopback) -> 127.0.0.1 is correct
                }
            }
        } catch (Exception _e) { /* keep config fallback */ }
        this.server = server;
        this.socket = socket;
        System.out.println(tag() + " Client Connected: " + this.socket.getInetAddress().toString()
                + ":" + (this.socket.getPort() & 0xffff));
        Thread thread = new Thread() {
            public void run() {
                try {
                    startRecieving();
                } catch (IOException ex) {
                    System.out.println(ex);
                }
                exit();
            }
        };
        thread.start();
    }

    /**
     * AI-bot roster stand-in (Sec 278): a socket-less Client that occupies a room slot so the existing
     * RoomCharsAck builder seats it exactly like a real player - the client seats each roster entry by its
     * [+0] slot byte (= the user's index in the room array). No networking: write() no-ops because out is
     * null. Distinct session_key -> distinct getSessionId(), which the client uses as the per-user roster
     * identity (duplicate ids/slots make the client abort the whole roster parse).
     */
    public Client(String botName, int botSeed)
    {
        this.isBot = true;
        this.loginName = botName;
        this.callSign = botName;
        // Unique sessionId per bot: key[0] in a reserved high range, key[1]=0x08 like real users, so
        // getSessionId() = (key[0]&0xff) + 0x800 is unique per bot and clear of the real-user range.
        session_key[0] = (byte) (0xE0 + (botSeed & 0x1f));
        session_key[1] = 0x08;
        session_key[2] = 0x07;
        session_key[3] = (byte) 0xF0;
        session_key[4] = 0x18;
        session_key[5] = 0x04;
        // Sec 290: remote-body RENDER is peer-gated (client sets player+0x50=3 only after the connected-UDP
        // CONNECT/ACCEPT+ms handshake completes for that party - Binary). The body is already built; it just
        // doesn't PAINT without the peer link. The client sends its Titan connection-request to the bot's
        // advertised endpoint, so the bot's roster ip/udpPort MUST point at our match relay (127.0.0.1:40708);
        // the existing processUdp then answers the CONNECT+ms AS the bot (routed by session token). ip/udpIp =
        // 127.0.0.1 (0x0100007F -> wire 7f 00 00 01), udpPort = 40708 (the relay the client tunnels to).
        this.Ip = 0x0100007F;
        // Sec 294 (final answer): peer-connect gate = FUN_3418e680 -> FUN_3415e120, which formats a 12-byte
        // block IP1(+0x6c)·port1(+0x9a)·IP2(+0x94)·port2(+0x34) = getIp·getUdpPort·getUdpIp·getUdpPort and
        // hands it to the engine -> peerRecord+0x18. A ZERO block -> null endpoint -> no connect (that was the
        // bot's real problem). Loopback is FINE (the probe connected at 127.0.0.1 - the loopback-skip theory
        // was disproven). So the bot's block must be a REACHABLE endpoint the server answers as the bot:
        // 127.0.0.1:40709 (the bot-peer listener). Also requires entry+0x1b6 != 0xFF (slot occupied) and the
        // same block mirrored in the 0x1b08 START payload (FUN_3420bff0 validates it before confirming).
        this.udpIp   = 0x0100007F;      // 127.0.0.1 (IP2 / local) - reachable, the bot-peer listener
        this.udpPort = (short) 40709;   // port1 & port2 - the 40709 listener the server answers on
        // No socket, no in/out, no receive thread - this Client never touches the network.
    }

    public boolean isBot() { return isBot; }

    public synchronized void setPendingCharacterLoadout(int red, int blue) {
        pendingCharacterLoadout = new int[]{red, blue};
    }

    public synchronized int[] consumePendingCharacterLoadout() {
        int[] value = pendingCharacterLoadout;
        pendingCharacterLoadout = null;
        return value;
    }

    private boolean readN(byte[] dst, int n) {
        try { in.readFully(dst, 0, n); return true; }
        catch (Exception e) { return false; }
    }

    public void startRecieving() throws IOException
    {
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        byte[] hdrWire = new byte[8];
        while (true)
        {
            if (!readN(hdrWire, 8)) { System.out.println(tag() + " RX closed / end of stream"); break; }

            // decrypt the 8-byte header -> real fields
            byte[] hdr;
            try {
                hdr = BrokerCrypto.decodeHeader(hdrWire);
            } catch (Exception e) {
                System.out.println(tag() + " RX header decode err: " + e + " -> stopping");
                break;
            }
            int msgId   = BrokerCrypto.msgId(hdr);
            int seq     = BrokerCrypto.seq(hdr);
            int blocks  = BrokerCrypto.payloadBlocks(hdr);
            boolean cks = BrokerCrypto.checksumOk(hdr);

            StringBuilder hs = new StringBuilder();
            for (byte b : hdr) hs.append(String.format("%02x ", b & 0xff));
            Log.wire(tag() + " RX msgId=" + msgId + " (0x" + Integer.toHexString(msgId) + ")"
                    + " seq=" + seq + " payBlocks=" + blocks + " cks=" + (cks ? "OK" : "BAD")
                    + "  hdr=" + hs.toString().trim());

            if (!cks) {
                // bad checksum = we mis-decoded (or stream desync). Don't die - resync on next 8 bytes.
                System.out.println(tag() + " RX bad checksum -> resyncing (skipping this header)");
                continue;
            }

            byte[] payload = new byte[0];
            if (blocks > 0) {
                int n = blocks * 16;
                if (n > recv_buffer.length) {
                    Log.wire(tag() + " RX payload too big (" + n + " > " + recv_buffer.length
                            + ") -> draining, staying connected");
                    int remaining = n;
                    byte[] sink = new byte[4096];
                    while (remaining > 0) {
                        int got;
                        try { got = in.read(sink, 0, Math.min(sink.length, remaining)); }
                        catch (Exception e) { System.out.println(tag() + " RX drain err: " + e); break; }
                        if (got < 0) break;
                        remaining -= got;
                    }
                    continue;
                }
                if (!readN(recv_buffer, n)) { System.out.println(tag() + " RX short payload"); break; }
                try {
                    // Per-frame payload key = whiten(hdr)||hdr, from this frame's decoded header.
                    payload = BrokerCrypto.decodePayload(Arrays.copyOfRange(recv_buffer, 0, n), hdr);
                    StringBuilder bs = new StringBuilder();
                    for (int i = 0; i < payload.length; i++) bs.append(String.format("%02x ", payload[i] & 0xff));
                    Log.wire(tag() + " RX payload(" + payload.length + ")=" + bs.toString().trim());
                } catch (Exception e) {
                    Log.wire(tag() + " RX payload decode err: " + e + " -> continuing");
                    continue;
                }
            }

            try {
                // Dispatch exactly this frame's decoded payload. Reusing recv_buffer leaked
                // trailing bytes from older frames into parsers and diagnostics.
                server.processMsg(this, msgId, payload);
            } catch (Exception e) {
                System.out.println(tag() + " RX processMsg err (ignored): " + e);
            }
        }
    }

    // ServerMsg.getData() returns a PLAINTEXT frame (8-byte header + payload).
    // We encode the header (whiten+Blowfish) and payload (AES) before sending.
    public synchronized void write(byte buffer [], short size)
    {
        if (out == null) return;   // bot stand-in has no socket - nothing to send
        try {
            byte[] plain = Arrays.copyOfRange(buffer, 0, size);
            int txMsgId = (plain.length >= 3) ? ((plain[1] & 0xff) | ((plain[2] & 0xff) << 8)) : -1;
            byte[] wire = BrokerCrypto.encodeFrame(plain);
            Log.wire(tag() + " TX msgId=" + txMsgId + " (0x" + Integer.toHexString(txMsgId) + ")"
                    + " plain=" + size + "B -> wire=" + wire.length + "B");
            // Dump the PLAINTEXT payload bytes we're sending (everything after the 8-byte header),
            // so we can see exactly what each ack carries (e.g. the 0x1301 LoginAck contents).
            if (plain.length > 8) {
                StringBuilder ts = new StringBuilder();
                for (int i = 8; i < plain.length; i++) ts.append(String.format("%02x ", plain[i] & 0xff));
                Log.wire(tag() + " TX payload(" + (plain.length - 8) + ")=" + ts.toString().trim());
            }
            out.write(wire, 0, wire.length);
            out.flush();
        } catch (Exception ex) {
            System.out.println(ex);
        }
    }

    /** Per-connection log tag derived from the server port: 40706=BRK, 40707+=GAME, else the port. */
    private String tag()
    {
        int p = (server != null) ? server.port : -1;
        String base;
        if (p == 40706) base = "[BRK";
        else if (p >= 40707 && p <= 40709) base = "[GAME:" + p;
        else if (p == 8444)  base = "[LOGIN";
        else base = "[" + p;
        // append the client's remote (ephemeral) port so each distinct SOCKET is identifiable in the log.
        // Two connections from the same player show different /c<port> tags -> we can see channel vs game wire.
        return base + "/c" + (Port & 0xffff) + "]";
    }

    public synchronized short getSeqNum()
    {
        short temp = seqNum;
        seqNum++;
        return temp;
    }

    public byte [] getSessionKey()
    {
        return session_key;
    }

    public void setLoginName(String loginName)
    {
        this.loginName = loginName;
    }

    public String getLoginName()
    {
        return loginName;
    }

    public boolean isUdpKicked() { return udpKicked; }
    public void setUdpKicked(boolean v) { this.udpKicked = v; }

    public String getCallsign()
    {
        return callSign;
    }

    public byte getUserLevel()
    {
        return userLevel;
    }
    public void setUserLevel(byte level)
    {
        userLevel = level;
    }
    public void UdpAddr(int udpIp, short udpPort)
    {
        this.udpIp = udpIp;
        this.udpPort = udpPort;
        server.processMsg(this, ClientMsg.CS_CH_UDPADDR_REQ, null);
    }

    public int getIp() {
        return Ip;
    }

    /** The interface this client connected on, as an addInteger()-order int (127.0.0.1 local / Hamachi remote). */
    public int getAdvertisedIp() { return advertisedIp; }
    /** Same, as wire bytes for byte-array payloads: {octet0, octet1, octet2, octet3}. */
    public byte[] getAdvertisedIpBytes() {
        return new byte[]{ (byte)(advertisedIp & 0xff), (byte)((advertisedIp>>8)&0xff),
                           (byte)((advertisedIp>>16)&0xff), (byte)((advertisedIp>>24)&0xff) };
    }

    public short getPort() {
        return Port;
    }

    public int getUdpIp() {
        return udpIp;
    }

    public short getUdpPort() {
        return udpPort;
    }

    public int  getRelayPort()      { return relayPort; }
    public void setRelayPort(int p) { this.relayPort = p; }

    public void setRoomNumber(short roomNumber) {
        this.roomNumber = roomNumber;
    }
    public void setRoomSlot(short roomSlot) {
        this.roomSlot = roomSlot;
    }
    public void setRoomTeam(byte roomTeam) {
        this.roomTeam = roomTeam;
    }
    public void setRoomTeamSlot(byte roomTeamSlot) {
        this.roomTeamSlot = roomTeamSlot;
    }
    public void setRoomSlotStatus(byte roomSlotStatus)
    {
        this.roomSlotStatus = roomSlotStatus;
    }
    public void setUserId(int user_id)
    {
        this.user_id = user_id;
    }
    public void setCallsign(String callSign)
    {
        this.callSign = callSign;
    }
    public int getUserId()
    {
        return user_id;
    }
    public short getRoomNumber() {
        return roomNumber;
    }
    public short getRoomSlot() {
        return roomSlot;
    }
    public byte getRoomTeam() {
        return roomTeam;
    }
    public byte getRoomTeamSlot() {
        return roomTeamSlot;
    }
    public byte getRoomSlotStatus()
    {
        return roomSlotStatus;
    }
    public short getSessionId()
    {
        return (short) ((session_key[0] & 0xff) + (session_key[1]*0x100));
    }

    public void exit()
    {
        server.removeClient(this);
    }
}

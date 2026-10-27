/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import wtserver.client.ClientMsg;
import wtserver.client.ServerMsg;
import wtserver.database.Users;
import wtserver.database.Users.UserInfo;
import wtserver.server.*;
import wtserver.server.channel.CrestUserInfoAck;
import wtserver.server.channel.EventShopInfoAck;
import wtserver.server.channel.GetPowerUserInfoAck;
import wtserver.server.channel.LoginAck;
import wtserver.server.channel.LogoutAck;
import wtserver.server.channel.SelectCharAck;
import wtserver.server.channel.SnsAccountAck;
import wtserver.server.channel.UdpAddrAck;
import wtserver.server.channel.UdpSuccessAck;
import wtserver.server.channel.UserInfoAck;

/**
 *
 * @author MSI
 */
public class GameServer extends Server {
    
    private ArrayList<Room> rooms;
    
    public GameServer(int port)
    {
        super(port);
        INSTANCE = this;   // single match server; ack builders reach the per-player relay through this
        rooms = new ArrayList<Room>();
        Thread thread = new Thread() {
            public void run() {
                startUdpServer();
            }
        };
        Thread roomsThread = new Thread() {
            public void run() {
                try {
                    while(true)
                    {
                        Thread.sleep(1000);
                        for (int i = 0; i < rooms.size(); i++) {
                            Room room = rooms.get(i);
                            if (room != null) {
                                room.process();
                            }
                        }
                    }

                    
                }catch(Exception ex)
                {
                    System.out.println(ex.getMessage());
                }
            }
        };
        thread.start();
        roomsThread.start();
    }
    
    public void startUdpServer() {
        try {
            udpSocket = new DatagramSocket(port, ServerConfig.bindAddress());
            // Load the LithTech connected-UDP Blowfish schedule (dumped from the client at 0x6AE468).
            // With it we decrypt the client's CONNECT and encrypt a valid CONNECT-ACCEPT.
            try {
                byte[] sched = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("bf_schedule.bin"));
                matchCrypto = new LTMatchCrypto(sched, true, false);   // LE words, LE block (validated)
                Log.match("[MATCH] loaded bf_schedule.bin (" + sched.length + "B) - connected-UDP crypto ready");
            } catch (Exception e) {
                matchCrypto = null;
                Log.match("[MATCH] bf_schedule.bin NOT loaded (" + e + ") - match packets will only reflect");
            }
            // Second UDP listener on port+1 (40709) = the BOT PEER endpoint (Sec 291). The client keys peers
            // by IP:port and dedups a peer sharing its own relay (40708) -> never CONNECTs. Giving bots a
            // DISTINCT port makes the client fire a real connection-request there; we answer AS the bot from
            // THIS socket (reply must originate from the port the client connected to). Same processUdp/crypto.
            try {
                udpSocket2 = new DatagramSocket(port + 1, ServerConfig.bindAddress());
                Thread t2 = new Thread(() -> udpListenLoop(udpSocket2), "udp-botpeer-" + (port + 1));
                t2.setDaemon(true);
                t2.start();
                Log.match("[MATCH] bot-peer UDP listener up on " + (port + 1));
            } catch (Exception e) { Log.match("[MATCH] bot-peer listener bind failed: " + e); }

            // ATLAS: transparent p2p RELAY. Advertise the server's reachable (Hamachi) IP + RELAY_PORT
            // as every REAL peer's endpoint; both clients send their connected-UDP p2p here, and we forward
            // it opaquely to the other player (sending FROM the relay socket so the client sees it arrive
            // from the advertised endpoint). Fixes self-host (loopback) and any case where the two clients
            // can't reach each other directly. Per-connection crypto stays between the clients - we never
            // decrypt. NOTE: single-port relay is correct for 2 players; N>2 would need per-pair ports.
            RELAY_IP = detectRelayIp();
            try {
                relaySocket = new DatagramSocket(RELAY_PORT, ServerConfig.bindAddress());
                Thread rt = new Thread(() -> relayLoop(), "udp-relay-" + RELAY_PORT);
                rt.setDaemon(true);
                rt.start();
                Log.match("[RELAY] p2p relay up on " + RELAY_PORT + " advertising peer IP "
                        + ((RELAY_IP)&0xff) + "." + ((RELAY_IP>>8)&0xff) + "." + ((RELAY_IP>>16)&0xff) + "." + ((RELAY_IP>>24)&0xff));
            } catch (Exception e) { Log.match("[RELAY] bind failed on " + RELAY_PORT + ": " + e); }

            udpListenLoop(udpSocket);   // main match relay loop on 40708 (blocks)

        } catch (SocketException ex) {
            System.out.println(ex);
        }
    }

    /** Receive loop for a UDP socket; replies to any packet go out via that SAME socket. */
    private void udpListenLoop(DatagramSocket sock) {
        while (true) {
            byte[] receiveData = new byte[65507];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            try {
                sock.receive(receivePacket);
                processUdp(receivePacket, sock);
            } catch (IOException ex) {
                System.out.println(ex);
            }
        }
    }
    @Override
    public void startServer() {
        try {
            ServerSocket socket = new ServerSocket(port, 50, ServerConfig.bindAddress());
            System.out.println("Game Server Started on port " + port);
            while (true) {
                Socket connectionSocket = socket.accept();
                addClient(new Client(this, connectionSocket));
            }
        } catch (IOException ex) {
            System.out.println(ex);
        }
    }
    
    @Override
    public void processMsg(Client client, int msgId, byte buffer [])
    {
        if (ServerConfig.CLIENT_2012 && msgId == 0x1418) {
            byte[] ack=Packet2012.equipmentCheck(client.getSeqNum(),client.getUserId());
            client.write(ack,(short)ack.length);
            return;
        }
        if (ServerConfig.CLIENT_2012 && msgId == 0x1338) {
            byte[] ack=Packet2012.status(0x1339,client.getSeqNum(),0);
            client.write(ack,(short)ack.length);
            return;
        }
        if (msgId == ClientMsg.CS_CH_LOGIN_REQ) {
            LoginAck loginAck = new LoginAck(buffer);
            client.setLoginName(loginAck.getLoginName());
            System.out.println("Login Name " + loginAck.getLoginName());
            byte errorcode = 0;
            Users users = new Users();
            // Passwordless Radmin profiles are resolved by the canonical local
            // player name. The client token is opaque and is never a password.
            int user_id = users.getLogin(loginAck.getLoginName(), loginAck.getLoginPassword());
            System.out.println("IP : " + client.getIp() + " User DB ID " + user_id);
            if(user_id <= 0)
                errorcode = 23;
            else {
                UserInfo userInfo = users.getUserInfo(user_id);
                client.setUserId(user_id);
                client.setCallsign(userInfo.callsign);
                client.setUserLevel((byte) userInfo.level);
                // (Login-time currency cache omitted: GetGoldCash reads the DB fresh, so no cache needed.)
                // Populate the login ack with the player's REAL data so the 0x1301 carries a valid
                // callsign + stats (the client builds the channel scene from these). Without real,
                // correctly length-prefixed strings the client's wide-string parse overruns and hangs.
                loginAck.setUserData(userInfo.callsign, userInfo.level, userInfo.gp,
                                     userInfo.gold, userInfo.wc, userInfo.cash);
                // Put the user_id at 0x1301 payload[3:7]. The client echoes those 4 bytes into the
                // LAST 4 bytes of its UDP session key, so we match the UDP packet on user_id (the
                // first 2 UDP key bytes are computed client-side - see PROJECT_LOG - so we match on
                // the 4 bytes we control).
                loginAck.setUdpKey(user_id);
            }
            client.write(loginAck.getData(client.getSeqNum(), errorcode, client.getSessionKey()), loginAck.getSize());
            if (ServerConfig.CLIENT_2012 && errorcode == 0) {
                // The 2012 channel flow expects this bootstrap immediately after
                // LoginAck. The previous client-compatible host sent 0x130D here;
                // waiting for a later client request leaves lobby entry stalled.
                UdpAddrAck udpBootstrap = new UdpAddrAck();
                client.write(udpBootstrap.getData(client.getSeqNum()), udpBootstrap.getSize());
                System.out.println("[UDPBOOT] sent 2012 0x130D after LoginAck");
            }
        }else if (msgId == ClientMsg.CS_CH_UDPADDR_REQ) {
            UdpAddrAck udpAddrAck = new UdpAddrAck();
            client.write(udpAddrAck.getData(client.getSeqNum()), udpAddrAck.getSize());
        }else if (msgId == ClientMsg.CS_CK_UDPSUCCESS_REQ) {
            // 0x1201 UDPSUCCESS_ACK is the match-UDP DESTINATION handoff (Ghidra: handler FUN_3408b7f0 ->
            // FUN_3415E060 -> connection-address setup). Payload = [globalIP:u32][globalPort:u16][localIP:u32]
            // [localPort:u16]; the client feeds these straight into the match sockaddr. So IP goes network-order
            // (7F 00 00 01 == addInteger(0x0100007F)) and PORT must be htons'd. Send global==local==127.0.0.1:40708
            // so the client takes the direct (flag-0) path and aims match UDP at our loopback server instead of
            // the uninitialized garbage (174.142.118.134) it was hitting. Previously we sent the client's OWN
            // address, which for the joiner is 0.0.0.0:0 -> garbage dest -> C114.
            final int   LOOPBACK_IP = client.getAdvertisedIp();                          // interface THIS client connected on
            final int   matchPort   = 40707;                                          // server relay UDP port
            final short MATCH_PORT  = (short) (((matchPort & 0xff) << 8) | ((matchPort >> 8) & 0xff)); // htons
            UdpSuccessAck udpSuccessAck = new UdpSuccessAck();
            client.write(udpSuccessAck.getData(client.getSeqNum(), LOOPBACK_IP, MATCH_PORT, LOOPBACK_IP, MATCH_PORT), udpSuccessAck.getSize());
            System.out.println("[UDPBOOT] 0x1200 from " + client.getLoginName()
                    + " -> 0x1201 UDPSUCCESS_ACK (127.0.0.1:40707, global==local)");
        }else if (msgId == ClientMsg.CS_CH_USERINFO_REQ) {
            UserInfoAck userInfoAck = new UserInfoAck(client.getCallsign());
            client.write(userInfoAck.getData(client.getSeqNum(), client), userInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_CRESTUSERINFO_REQ) {
            CrestUserInfoAck crestUserInfoAck = new CrestUserInfoAck();
            client.write(crestUserInfoAck.getData(client.getSeqNum()), crestUserInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_ITEMLIST_REQ) {
            System.out.println("Item List Request");
            ItemListAck itemListAck = new ItemListAck(client.getUserId());
            client.write(itemListAck.getData(client.getSeqNum()), itemListAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_EQUIPLIST_REQ) {
            EquipListAck equipListAck = new EquipListAck();
            client.write(equipListAck.getData(client.getSeqNum(), client.getUserId()), equipListAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_CHARITEMLIST_REQ) {
            CharItemListAck charItemListAck = new CharItemListAck(client.getUserId());
            client.write(charItemListAck.getData(client.getSeqNum()), charItemListAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_CHARINFO_REQ) {
            CharInfoAck charInfoAck = new CharInfoAck(client.getUserId());
            client.write(charInfoAck.getData(client.getSeqNum()), charInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_GETPOWERUSERINFO_REQ) {
            GetPowerUserInfoAck getPowerUserInfoAck = new GetPowerUserInfoAck(client.getUserId());
            client.write(getPowerUserInfoAck.getData(client.getSeqNum()), getPowerUserInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_SELECTCHAR_REQ) {
            SelectCharAck selectCharAck = new SelectCharAck();
            client.write(selectCharAck.getData(client.getSeqNum(), client.getAdvertisedIpBytes()), selectCharAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_CRM_POPUP_REQ) {
            PopUpAck popUpAck = new PopUpAck();
            client.write(popUpAck.getData(client.getSeqNum()), popUpAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_FIELDLIST_REQ) {
            FieldListAck fieldListAck = new FieldListAck(rooms);
            client.write(fieldListAck.getData(client.getSeqNum()), fieldListAck.getSize());
        }else if (msgId == ClientMsg.CS_CN_ADVERTALL_REQ) {
            ChannelCharsAck channelCharsAck = new ChannelCharsAck();
            client.write(channelCharsAck.getData(client.getSeqNum(), getChannelUsers()), channelCharsAck.getSize());
            AdvertAllAck advertAllAck = new AdvertAllAck();
            client.write(advertAllAck.getData(client.getSeqNum()), advertAllAck.getSize());
        }else if (msgId == ClientMsg.CS_CN_CHANGESHEET_REQ) {
            ChangeSheetAck changeSheetAck = new ChangeSheetAck(buffer);
            client.write(changeSheetAck.getData(client.getSeqNum()), changeSheetAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_CHAT_REQ) {
            ChannelChatAck channelChatAck = new ChannelChatAck(buffer, client.getLoginName());
            int n = client.getRoomNumber();
            if(n == -1)
                writeToChannel(channelChatAck);
            else {
                Room room = getRoomWithNumber((short) n);
                if(room != null)
                {
                    room.write(channelChatAck);
                    if(channelChatAck.getText().equalsIgnoreCase("autowin"))
                    {
                        room.endRound(client.getRoomTeam(), true);
                    }
                }
            }
            //client.write(channelChatAck.getData(client.getSeqNum()), channelChatAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_CREATE_REQ) {
            // dump the full CREATE payload so we can diff plain-Deathmatch vs AI-Deathmatch and find the AI flag
            if (buffer != null) {
                StringBuilder cd = new StringBuilder();
                for (byte b : buffer) cd.append(String.format("%02x ", b & 0xff));
                System.out.println("[CREATE payload " + buffer.length + "B] " + cd.toString().trim());
            }
            CreateAck createAck = new CreateAck(buffer);
            Room room = createRoom(createAck);
            if (ServerConfig.CLIENT_2012) {
                byte[] ack=Packet2012.createRoom(client.getSeqNum(),room.getRoomNumber());
                client.write(ack,(short)ack.length);
            } else client.write(createAck.getData(client.getSeqNum()), createAck.getSize());
            room.addUser(client);
            client.setRoomNumber(room.getRoomNumber());
            RoomCharsAck charsAck = new RoomCharsAck();
            client.write(charsAck.getData(client.getSeqNum(), room), charsAck.getSize());
            FieldListAck fieldListAck = new FieldListAck(rooms);
            client.write(fieldListAck.getData(client.getSeqNum()), fieldListAck.getSize());
            writeToChannel(fieldListAck);
        }else if (msgId == ClientMsg.CS_FD_UDPSTART_REQ) {
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
                UdpStartAck udpStartAck = new UdpStartAck(room.getUserSlot(client));
                room.write(udpStartAck);
            }else System.out.println("CS_FD_UDPSTART_REQ Room is Null " + n);
        }else if (msgId == ClientMsg.CS_FD_INITFIELD_REQ) {
            // INITFIELD (0x165C) is a POST-start field-load step, NOT the start trigger.
            // Ghidra (FUN_3423e530): the client's 0x165D handler is a header-only no-op, so a
            // bare InitFieldAck is correct. The start is CLIENT-DRIVEN: client sends STARTGAME_REQ
            // (0x1618, bare) -> server replies 0x1619 (handled below). The old auto-start-on-INITFIELD
            // was inverted (it would double-fire StartGameAck + fight the real host->start->load order),
            // so it's removed. Reply header-only 0x165D only.
            System.out.println("Init Field");
            InitFieldAck initFieldAck = new InitFieldAck();
            client.write(initFieldAck.getData(client.getSeqNum()), initFieldAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_ADD_AISLOT_REQ) {
            // REQ (0x1674) is [AI type][client-local value][difficulty][requested team].
            // logs15 proves byte 3 changes 1/2 with the selected team panel;
            // byte 2 remains the difficulty index. Preserve the requested side.
            int nameIdx = (buffer != null && buffer.length > 0) ? (buffer[0] & 0xff) : 1;
            int difficulty = (buffer != null && buffer.length > 2) ? (buffer[2] & 0xff) : 1;
            int requestedTeam = (buffer != null && buffer.length > 3) ? (buffer[3] & 0xff) : 0;
            short rn = client.getRoomNumber();
            Room aroom = getRoomWithNumber(rn);
            // Sec 280: roster-only seating WORKS - bots land at distinct slots 1,2 (Binary-confirmed) and are
            // VISIBLE, but render as PLAYERS not AI. The 0x1675 only added the "is-AI" tag (and its widget
            // path piled them), so it stays SUPPRESSED. Remaining gap = mark the roster entry as an AI bot
            // (flag in the RoomCharsAck record - Binary to name it: the role byte #1 / a per-user flag).
            StringBuilder _rq = new StringBuilder();
            if (buffer != null) for (int i = 0; i < buffer.length; i++) _rq.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("[AI] ADD_AISLOT req requestedTeam=" + requestedTeam + " type=" + nameIdx
                    + " difficulty=" + difficulty
                    + " | REQ(" + (buffer == null ? 0 : buffer.length) + ")=" + _rq.toString().trim()
                    + " | 0x1675 suppressed - seating via roster (bots seat at distinct slots as players)");
            if (aroom != null) {
                // Adding an AI bot means this MUST be an AI-Deathmatch room, not WolfHunt. If the room's base
                // mode is anything else (esp. WolfHunt 0x80), the client runs the wrong rules -> "need 4 ready",
                // no teams, AI hardcoded to the enemy side. Force base=Deathmatch (0x02) + AI flag (0x040000).
                if (BOT_FORCE_AIDM_MODE) {
                    int _old = aroom.mode;
                    aroom.mode = (aroom.mode & ~0xFF) | 0x02 | 0x040000;   // DM base + AI flag = 0x..040202
                    if (aroom.mode != _old)
                        System.out.println("[MODE] AI bot added -> forced room to AI-Deathmatch 0x"
                                + Integer.toHexString(aroom.mode) + " (was 0x" + Integer.toHexString(_old)
                                + ", Wolfhunt=" + aroom.isWolfhunt() + ")");
                }
                // Team ownership follows the clicked panel exactly. Capacity
                // checks remain in Room.addBot; no forced balancing is used.
                int teamOneCount = 0;
                int teamTwoCount = 0;
                if (aroom.users != null) {
                    for (Client u : aroom.users) {
                        if (u == null) continue;
                        if (u.getRoomTeam() == 1) {
                            teamOneCount++;
                        } else if (u.getRoomTeam() == 2) {
                            teamTwoCount++;
                        }
                    }
                }
                byte serverTeam;
                // Balance the bots themselves rather than repeatedly
                // compensating for the host. With one red host, total-count
                // balancing picked blue twice (0/1 then 1/1). This alternates
                // bot sides deterministically: blue, red, blue, red …
                if (requestedTeam != 1 && requestedTeam != 2) {
                    System.out.println("[AI] rejected invalid requested team=" + requestedTeam);
                    return;
                }
                serverTeam = (byte) requestedTeam;
                System.out.println("[AI] team decision requestByte=" + requestedTeam
                        + " counts blue/red=" + teamOneCount + "/" + teamTwoCount
                        + " -> serverTeam=" + serverTeam);
                if (BOT_FORCE_TEAM >= 0) {   // test lever: make the team vary to map team->color
                    serverTeam = (byte) BOT_FORCE_TEAM;
                    System.out.println("[AI] BOT_FORCE_TEAM: overriding serverTeam -> " + serverTeam
                                       + " (mapping team value to rendered color)");
                }
                // Sec 296 TEST: field read showed 2 bots byte-identical except slot (both nameIdx=5) -> only 1
                // body paints. Hypothesis: the AI body builder buckets by TYPE (nameIdx), so same-type bots
                // collapse. Force a DISTINCT nameIdx per bot to test distinct-type -> distinct-body. UI is
                // auth-locked to easy, but the client seats whatever nameIdx the ACK carries (no auth re-check
                // on the response). REVERT: change testNameIdx back to nameIdx in both addBot + AddAISlotAck.
                int botCount = 0;
                if (aroom.users != null) for (Client u : aroom.users) if (u != null && u.isBot()) botCount++;
                int testNameIdx = nameIdx;   // REVERTED Sec 296: echo the client's exact nameIdx. It's already the human-easy entry the UI sent; + botCount pushed it past the name table (7,8..) -> blank AI (no name/type/difficulty).
                Client bot = aroom.addBot(serverTeam, testNameIdx);
                if (bot != null) {
                    // AI ACK uses a compact bool: 1=blue, 0=red. Earlier
                    // inversion sent blue as 0 and rendered it red.
                    int aiWireTeam = (serverTeam == 1) ? 1 : 0;
                    // Sec 294: use the SAME proven path real players use on join - broadcast the FULL roster
                    // (0x1605 RoomCharsAck). The additive 0x1607 (EnterSeatAck) double-dispatched on the client
                    // (one frame -> TWO roster entries, Binary-confirmed) AND read its block at the wrong offset
                    // for the additive parser (FUN_3418b9b0). The full RoomCharsAck is single-dispatch
                    // (FUN_3418f8f0 -> FUN_3418f2c0 per entry), puts the connection block right after the 3 name
                    // strings (the layout the host's block dumped correctly from: 0100007F:706F), and its tail
                    // FUN_3418e680(0) loops the roster and connects EVERY peer incl the bot (127.0.0.1:40709)
                    // via FUN_3415e120. Real players survive this same 0x1605 on join, so an existing client does too.
                    // NATIVE 0x1675 AI-slot add (parser-confirmed: FUN_342413c0 -> FUN_3418e4e0). Fires the
                    // client's AI path: names the AI from its string table by nameIdx (rec+0x70) and runs the AI
                    // widget updates. Real players keep using 0x1605 on join - untouched.
                    // REVERT if a bot fails to seat: RoomCharsAck ca = new RoomCharsAck(); aroom.write(ca);
                    // difficulty 1 = EASY (UI is auth-locked to easy). botNumber = botCount+1 so names are "AI EASY 1", "AI EASY 2", ...
                    // FLAG-HYBRID (Atlas SLOT_TRACE): scoreboard is sessionId-keyed, bot sessionId reaches the
                    // client as 0 (0x1675 has no id field) -> 2 unmerged rows. Broadcast the FULL roster FIRST
                    // so the bot enters the sessionId-keyed roster with its real distinct id (getSessionId,
                    // 0x8E0+seed) -> rows merge to ONE. Then fire 0x1675 LAST to stamp the AI-type (rec+0x70) so
                    // the render stays local-AI. Toggle: GameServer.BOT_SEAT_MODE.
                    // ROSTER (0x1605): identity object. Sent in modes 1 (AI_PLUS_ROSTER) and 2 (ROSTER_ONLY).
                    if (BOT_SEAT_MODE == 1 || BOT_SEAT_MODE == 2) {
                        RoomCharsAck botIdentityRoster = new RoomCharsAck();
                        aroom.write(botIdentityRoster);   // 0x1605 full roster -> bot seated as an identity player
                        System.out.println("[AI] seat: roster (0x1605) for bot '" + bot.getLoginName()
                                + "' sessionId 0x" + Integer.toHexString(bot.getSessionId() & 0xffff)
                                + " slot " + bot.getRoomSlot() + " team " + bot.getRoomTeam());
                    }
                    // AI-SLOT (0x1675): the AI/widget object. Sent in modes 0 (AI_ONLY) and 1 (AI_PLUS_ROSTER).
                    // Mode 2 (ROSTER_ONLY) drops it -> only ONE object -> no duplicate (server-puppet foundation).
                    if (BOT_SEAT_MODE == 0 || BOT_SEAT_MODE == 1) {
                        // Byte 0 of 0x1674 is the client's selected AI type/name index.  Returning
                        // a hard-coded 1 here turned every selection into AI EASY and discarded the
                        // chosen human/wolf and difficulty variant.
                        AddAISlotAck aiAck = new AddAISlotAck((byte) bot.getRoomSlot(), (byte) aiWireTeam,
                                (byte) testNameIdx, (byte) (botCount + 1));
                        aroom.write(aiAck);
                        System.out.println("[AI] native 0x1675 AI-slot add for bot '" + bot.getLoginName()
                                + "' @ slot " + bot.getRoomSlot() + " nameIdx=" + testNameIdx
                                + " (base=" + nameIdx + " botIdx=" + botCount + ") team=" + aiWireTeam
                                + " [serverTeam=" + serverTeam + ", counts=" + teamOneCount + "/" + teamTwoCount + "]");
                    }
                    System.out.println("[AI] seat mode=" + BOT_SEAT_MODE
                            + (BOT_SEAT_MODE == 2 ? " (ROSTER_ONLY / server-puppet foundation - one object)"
                             : BOT_SEAT_MODE == 1 ? " (AI_PLUS_ROSTER - two objects, expect duplicate)"
                             : " (AI_ONLY - original)"));
                    // History (Sec 294/295): firing additive 0x1607 double-dispatched AND flipped to player
                    // render. We use single-dispatch 0x1605 instead, and ROSTER_ONLY drops 0x1675 entirely so
                    // there is only one object to begin with. Do NOT use 0x1607 for bots.
                }
            }
        }else if (msgId == ClientMsg.CS_FD_CHANGERULE_REQ) {
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if (room != null) {
                ChangeRuleAck changeRuleAck = new ChangeRuleAck(buffer);
                room.write(changeRuleAck);
                room.title = changeRuleAck.getRoomTitle();
                room.password = changeRuleAck.getRoomPassword();
                room.map = changeRuleAck.getMap();
                room.time = changeRuleAck.getTime();
                room.wins = changeRuleAck.getWins();
                room.points = changeRuleAck.getPoints();
                room.mode = (room.mode & ~0xFF) | (changeRuleAck.getMode() & 0xFF);   // ATLAS: preserve upper bytes (AI-DM flag 0x040000) - was masking to 0xFF and stripping it
                System.out.println("[MODE] ChangeRule -> room mode now 0x" + Integer.toHexString(room.mode)
                        + " lowByte=0x" + Integer.toHexString(room.mode & 0xFF) + " (0x80=WolfHunt) Wolfhunt="
                        + room.isWolfhunt() + " AiDeathMatch=" + room.isAiDeathMatch());
                room.roomCapacity = changeRuleAck.getCapacity();
            }
        }else if (msgId == ClientMsg.CS_FD_STARTGAME_REQ) {
            System.out.println("Start Game");
            StartGameAck startGameAck = new StartGameAck();
            //client.write(startGameAck.getData(client.getSeqNum()), startGameAck.getSize());
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
            client.setRoomSlotStatus((byte)1);
                boolean hasBothTeams = room.getTeamCount((byte)1) > 0 && room.getTeamCount((byte)2) > 0;
                if (hasBothTeams && (room.getTeamReadyCount((byte)1) < 1 || room.getTeamReadyCount((byte)2) < 1)) {
                    startGameAck.setErrorCode(StartGameAck.ATLEAST_2_READY);
                    client.write(startGameAck.getData(client.getSeqNum()), startGameAck.getSize());
                    client.setRoomSlotStatus((byte)0);
                } else {
                    room.startGame();
                    startGameAck.setRoomData(room.map, (byte) room.mode);
                    room.write(startGameAck);
                }

            }else System.out.println("CS_FD_STARTGAME_REQ Room is Null " + n);
        }else if (msgId == ClientMsg.CS_FD_STARTROUND_REQ) {
            System.out.println("Start Round");
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
                room.startRound();
                StartRoundAck startRoundAck = new StartRoundAck(room.getRoundNumber(), room.getCurrentTime());
                ChangeSlotStatus changeSlotStatus = new ChangeSlotStatus(room.getUserSlot(client), (byte)4);
                client.setRoomSlotStatus((byte)4);
                room.write(changeSlotStatus);
                client.write(startRoundAck.getData(client.getSeqNum()), startRoundAck.getSize());
                // Spawn the requesting player with a VALID character so it gets a model + HP (fixes the
                // in-field 0-HP / can't-move bug). The client's spawn handler (Ghidra FUN_3423a4c0) reads
                // the char id at payload[0x12]; char id 0 -> disabled char (validity -1) -> 0 HP. Broadcast
                // this player's spawn to the whole room; each client's own STARTROUND_REQ covers itself, so
                // everyone ends up spawned for everyone (no N^2 fan-out).
                // TEAM MAPPING FIX (monke observed live): the char sent to team 1 showed up on the BLUE
                // side and team 2 on RED -> so team 1 = BLUE, team 2 = RED (my earlier red/blue labels were
                // inverted). Chars are faction-locked, so the inverted mapping handed RED a blue-only char
                // (30005) -> faction reject -> no char -> 0 HP. Correct mapping: team1->30005 (Blue Basic),
                // team2->30006 (Red Basic).
                // Roster and SpawnAck resolve the same persisted red/blue choice.
                // Picking the first faction match would make arbitrary characters
                // appear hardcoded whenever the inventory contains several skins.
                short spawnCharId = CharacterResolver.resolve(client.getUserId(), client.getRoomTeam());
                SpawnAck spawnAck = new SpawnAck((byte) room.getRoomMaster(), (byte) client.getRoomSlot(), spawnCharId);
                room.write(spawnAck);
                System.out.println("[SPAWN] user=" + client.getUserId() + " slot=" + client.getRoomSlot()
                                   + " team=" + client.getRoomTeam() + " (1=blue,2=red)"
                                   + " -> selected char=" + spawnCharId + " msgId=0x"
                                   + Integer.toHexString(SpawnAck.SPAWN_MSGID & 0xffff));
                // Spawn the AI bots (once per round) - they can't send their own STARTROUND_REQ. Safe now
                // that SpawnAck's +8 result byte is 0 (Sec 283); previously the bot's slot landed on +8 -> 4001.
                room.spawnBots();
            }else System.out.println("CS_FD_STARTROUND_REQ Room is Null " + n);
        }else if (msgId == ClientMsg.CS_FD_UDPREADY_REQ) {
            System.out.println("Getting Ready");
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
                UdpReadyAck udpReadyAck = new UdpReadyAck(room.getUserSlot(client), buffer);
                room.write(udpReadyAck);
                client.setRoomSlotStatus(udpReadyAck.userReady());
                if (room.gameStarted) {
                    EnterGameAck enterGameAck = new EnterGameAck((byte) client.getRoomSlot(), client.getRoomTeam(), client.getRoomTeamSlot());
                    room.write(enterGameAck);
                    client.setRoomSlotStatus((byte) 3);
                }
            }else System.out.println("CS_FD_UDPREADY_REQ Room is Null " + n);
        }else if (msgId == ClientMsg.CS_FD_ENTERGAME_REQ) {
            System.out.println("Enter Game");
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
                EnterGameAck enterGameAck = new EnterGameAck((byte)client.getRoomSlot(), client.getRoomTeam(), client.getRoomTeamSlot());
                room.write(enterGameAck);
                client.setRoomSlotStatus((byte)3);
            }else System.out.println("CS_FD_ENTERGAME_REQ Room is Null " + n);
        }else if (msgId == ClientMsg.CS_FD_CHANGETEAM_REQ) {
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
            if(room != null)
            {
                room.changeTeam(client);
                ChangeTeamAck changeTeamAck = new ChangeTeamAck((byte)client.getRoomSlot(), client.getRoomTeam(), client.getRoomTeamSlot());
                //client.write(changeTeamAck.getData(client.getSeqNum()), changeTeamAck.getSize());
                room.write(changeTeamAck);
            }
        }else if (msgId == ClientMsg.CS_IN_BUYNOW_ITEMLIST_REQ) {
            BuyNowItemListAck buyNowItemListAck = new BuyNowItemListAck();
            client.write(buyNowItemListAck.getData(client.getSeqNum()), buyNowItemListAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_SNSACCOUNT_REQ) {
            SnsAccountAck snsAccountAck = new SnsAccountAck();
            client.write(snsAccountAck.getData(client.getSeqNum()), snsAccountAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_EVENTSHOPINFO_REQ) {
            EventShopInfoAck eventShopInfoAck = new EventShopInfoAck();
            client.write(eventShopInfoAck.getData(client.getSeqNum()), eventShopInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_ITEM_LIMITED_REQ) {
            ItemLimitedAck itemLimitedAck = new ItemLimitedAck();
            client.write(itemLimitedAck.getData(client.getSeqNum()), itemLimitedAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_PACKAGE_ITEM_LIST_REQ) {
            PackageItemListAck packageItemListAck = new PackageItemListAck();
            client.write(packageItemListAck.getData(client.getSeqNum()), packageItemListAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_GETMACROMESSAGE_REQ) {
            GetMacroMessageAck getMacroMessageAck = new GetMacroMessageAck();
            client.write(getMacroMessageAck.getData(client.getSeqNum()), getMacroMessageAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_GETEVENTGOLDINFO_REQ) {
            GetEventGoldInfoAck getEventGoldInfoAck = new GetEventGoldInfoAck();
            client.write(getEventGoldInfoAck.getData(client.getSeqNum()), getEventGoldInfoAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_ITEMPRICE_VERSION_REQ) {
            ItemPriceVersionAck itemPriceVersionAck = new ItemPriceVersionAck();
            client.write(itemPriceVersionAck.getData(client.getSeqNum()), itemPriceVersionAck.getSize());
        }else if (msgId == ClientMsg.CS_PR_BATTLERESULT_REQ) {
            PrideBattleResultAck prideBattleResultAck = new PrideBattleResultAck();
            client.write(prideBattleResultAck.getData(client.getSeqNum()), prideBattleResultAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_EXIT_REQ) {
            short n = client.getRoomNumber();
            Room room = getRoomWithNumber(n);
                if (room != null) {
                    if(room.getUserSlot(client) != -1)
                    {
                        ExitAck exitAck = new ExitAck(room.getUserSlot(client));
                        room.write(exitAck);
                        room.removeUser(client);
            ChannelCharsAck channelCharsAck = new ChannelCharsAck();
            client.write(channelCharsAck.getData(client.getSeqNum(), getChannelUsers()), channelCharsAck.getSize());
                    }

                } else {
                    ExitAck exitAck = new ExitAck((byte)0);
                    client.write(exitAck.getData(client.getSeqNum()), exitAck.getSize());
                }

        }else if (msgId == ClientMsg.CS_CH_DISCONNECT_REQ) {
            DisconnectAck disconnectAck = new DisconnectAck();
            client.write(disconnectAck.getData(client.getSeqNum()), disconnectAck.getSize());
        }else if (msgId == ClientMsg.CS_CH_LOGOUT_REQ) {
            LogoutAck logoutAck = new LogoutAck();
            client.write(logoutAck.getData(client.getSeqNum()), logoutAck.getSize());
        }else if (msgId == ClientMsg.CS_GI_ITEM_COUNT_REQ) {
            GiftItemCountAck giftItemCountAck = new GiftItemCountAck();
            client.write(giftItemCountAck.getData(client.getSeqNum()), giftItemCountAck.getSize());
        }else if (msgId == ClientMsg.CS_FD_ENTER_REQ) {
            EnterAck enterAck = new EnterAck(buffer);
            System.out.println("Entering Room " + enterAck.getRoomNumber());
            short n = enterAck.getRoomNumber();
             Room room = getRoomWithNumber(n);
            if(room != null)
            {
                if(room.isFull())
                {
                    client.write(enterAck.getData(client.getSeqNum(), client, EnterAck.ROOM_FULL), enterAck.getSize());
                }else if(!room.isOpen)
                {
                    client.write(enterAck.getData(client.getSeqNum(), client, EnterAck.ALMOST_OVER), enterAck.getSize());
                }else {
                    RoomCharsAck charsAck = new RoomCharsAck();
                    room.addUser(client);
                    room.write(enterAck, client);
                    // Broadcast the roster to EVERYONE in the room (not just the joiner) so players
                    // already in the room see the new player appear in a slot. (Was: charsAck sent
                    // only to the joiner -> a joined player was invisible to existing players.)
                    room.write(charsAck);
                }
            }else client.write(enterAck.getData(client.getSeqNum(), client, EnterAck.DOES_NOT_EXIST), enterAck.getSize());
            
        }else if (msgId == ClientMsg.CS_FD_PLAYERDEAD_REQ)
        {
            PlayerDeadAck playerDeadAck = new PlayerDeadAck(buffer);
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                room.playerDead(client, playerDeadAck);
            }
        }else if (msgId == ClientMsg.CS_FD_REVIVALUSER_REQ)
        {
            RevivalAck revivalAck = new RevivalAck(buffer, client.getRoomTeam());
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                room.userRevived(client);
                room.write(revivalAck);
                //client.write(revivalAck.getData(client.getSeqNum()), revivalAck.getSize());
            }
        }else if (msgId == ClientMsg.CS_FD_PLANTBOMB_REQ)
        {
            PlantBombAck plantBombAck = new PlantBombAck();
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                room.write(plantBombAck);
                room.plantBomb();
            }
        }else if (msgId == ClientMsg.CS_FD_REMOVEBOMB_REQ)
        {
            RemoveBombAck removeBombAck = new RemoveBombAck();
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                room.write(removeBombAck);
                room.removeBomb();
            }
        }else if (msgId == ClientMsg.CS_FD_CHANGEFLAG_REQ)
        {
            ChangeFlagAck changeFlagAck = new ChangeFlagAck(buffer);
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                room.write(changeFlagAck);
                room.changeFlag(changeFlagAck.getFlag(), changeFlagAck.getTeam());
            }
        }else if (msgId == ClientMsg.CS_FD_GAMEPOINTS_REQ)
        {
            Room room = getRoomWithNumber(client.getRoomNumber());
            if(room != null)
            {
                GamePointsAck gamePointsAck = new GamePointsAck();
                //room.write(gamePointsAck);
                client.write(gamePointsAck.getData(client.getSeqNum(), client), gamePointsAck.getSize());
                System.out.println("gamePointsAck");
                //client.write(endGameAck.getData(client.getSeqNum()), endGameAck.getSize());
            }
        }else if (msgId == ClientMsg.CS_IN_GETGOLDCASH_REQ)
        {
            // Read the player's REAL currency from the DB instead of the login-time cache, so the shop
            // shows the same gold/wc/cash as everywhere else (kills the "free money" flicker on shop entry).
            Users goldUsers = new Users();
            UserInfo goldInfo = goldUsers.getUserInfo(client.getUserId());
            int dbGold = (goldInfo != null) ? goldInfo.gold : 0;
            int dbWc   = (goldInfo != null) ? goldInfo.wc   : 0;
            int dbCash = (goldInfo != null) ? goldInfo.cash : 0;
            GetGoldCashAck getGoldCashAck = new GetGoldCashAck(dbGold, dbWc, dbCash);
            client.write(getGoldCashAck.getData(client.getSeqNum()), getGoldCashAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_BUYITEM_REQ)
        {
            BuyItemAck buyItemAck = new BuyItemAck(buffer, client.getUserId());
            byte errorcode = buyItemAck.isSuccess() ? (byte) 0 : (byte) 1;
            client.write(buyItemAck.getData(client.getSeqNum(), errorcode), buyItemAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_CHANGE_ITEM_IN_INVEN_REQ)
        {
            ChangeItemInInvenAck changeItemInInvenAck = new ChangeItemInInvenAck(buffer);
            client.write(changeItemInInvenAck.getData(client.getSeqNum()), changeItemInInvenAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_INSTALL_ITEM_REQ) {
            // 0x140C is emitted once per weapon preset before the 0x1414
            // equipment snapshot. Ignoring it left the client with a pending
            // install transaction and caused newly equipped secondary weapons
            // to disappear on the next refresh.
            int uniqueId = readLittleEndianInt(buffer, 0);
            int preset = (buffer != null && buffer.length > 4) ? (buffer[4] & 0xff) : -1;
            System.out.println("[INVEN-INSTALL] user=" + client.getUserId()
                    + " unique=" + uniqueId + " preset=" + preset);
            InventoryActionAck installAck = new InventoryActionAck(ServerMsg.CS_IN_INSTALL_ITEM_ACK);
            client.write(installAck.getData(client.getSeqNum()), installAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_USE_ITEM_REQ) {
            // EX inventory use request (e.g. Lucky Box / Power Slot ticket).
            // Acknowledge the state transition so the client no longer waits
            // indefinitely or falls back to the desktop. Reward/consumption is
            // intentionally separate and will be committed only with a proven
            // item-result packet.
            int uniqueId = readLittleEndianInt(buffer, 0);
            int itemId = uniqueId >= 2000000000 ? uniqueId - 2000000000 : -1;
            System.out.println("[INVEN-USE] user=" + client.getUserId()
                    + " unique=" + uniqueId + " item=" + itemId);
            InventoryActionAck useAck = new InventoryActionAck(ServerMsg.CS_IN_USE_ITEM_ACK);
            client.write(useAck.getData(client.getSeqNum()), useAck.getSize());
        }else if (msgId == ClientMsg.CS_IN_EQUIP_REQ)
        {
            EquipAck equipAck = new EquipAck(buffer, client.getUserId());
            client.write(equipAck.getData(client.getSeqNum()), equipAck.getSize());
            }else if (msgId == 0x1438) {
            // 2012 CHARSAVE: count, then 69-byte entries consisting of a slot,
            // character unique and sixteen equipment uniques. Keep the legacy
            // donor parser below isolated from this client's native layout.
            if (ServerConfig.CLIENT_2012) {
                int[] selected = CharacterSave2012.save(buffer, client.getUserId());
                if (selected != null) {
                    Users charUsers = new Users();
                    charUsers.setCharacterLoadout(client.getUserId(), selected[0], selected[1]);
                    client.setPendingCharacterLoadout(selected[0], selected[1]);
                    System.out.println("[CHARLOAD] 0x1438 selected pair user=" + client.getUserId()
                            + " red=" + selected[0] + " blue=" + selected[1]);
                }
            } else if (buffer == null || buffer.length < 1) {
                System.out.println("CharSave ignored: empty payload");
            } else {
                // Slot 9 is an aggregate installed-character list. Slots 8
                // and 10 are the explicit blue/red default selectors echoed
                // by 0x143A; they must be retained so those keys resolve to
                // the selected item ID instead of a global inventory index.
                final int CHAR_SLOT_COUNT = 10;
                final int CHAR_ENTRY_SIZE = 69;
                int chCount = buffer[0] & 0xff;
                int completeEntries = (buffer.length - 1) / CHAR_ENTRY_SIZE;
                if (chCount > completeEntries) {
                    System.out.println("CharSave truncated: declared=" + chCount + " complete=" + completeEntries
                                       + " payload=" + buffer.length + "B");
                    chCount = completeEntries;
                }

                Users chUsers = new Users();
                java.util.HashSet<Integer> explicitSlots = new java.util.HashSet<Integer>();

                // Record 9 is not an inventory slot.  In every captured 0x1438
                // packet it contains the *two active faction characters*:
                //
                //   [09][red character unique][blue character unique] ...
                //
                // e.g. logs19: 09 77 09 36 77 60 09 36 77 => 30071 / 30048.
                // Treating the two fields as old/new values, then letting the
                // ambiguous 0x143A list indices overwrite them, is what made
                // the preview, room roster and spawned model disagree.  This
                // aggregate is the only packet field that carries both selected
                // item IDs rather than transient UI positions.
                for (int ci = 0; ci < chCount; ci++) {
                    int chp = 1 + (ci * CHAR_ENTRY_SIZE);
                    if ((buffer[chp] & 0xff) != 9) continue;
                    int firstUnique = (buffer[chp + 1] & 0xff)
                            | ((buffer[chp + 2] & 0xff) << 8)
                            | ((buffer[chp + 3] & 0xff) << 16)
                            | ((buffer[chp + 4] & 0xff) << 24);
                    int secondUnique = (buffer[chp + 5] & 0xff)
                            | ((buffer[chp + 6] & 0xff) << 8)
                            | ((buffer[chp + 7] & 0xff) << 16)
                            | ((buffer[chp + 8] & 0xff) << 24);
                    int first = firstUnique >= 2000000000 ? firstUnique - 2000000000 : 0;
                    int second = secondUnique >= 2000000000 ? secondUnique - 2000000000 : 0;
                    int red = CharacterFaction.isRedFaction(first) ? first
                            : (CharacterFaction.isRedFaction(second) ? second : 0);
                    int blue = CharacterFaction.isBlueFaction(first) ? first
                            : (CharacterFaction.isBlueFaction(second) ? second : 0);
                    if (red > 0 && blue > 0) {
                        chUsers.setCharacterLoadout(client.getUserId(), red, blue);
                        System.out.println("[CHARLOAD] 0x1438 aggregate user=" + client.getUserId()
                                + " red=" + red + " blue=" + blue);
                    } else {
                        System.out.println("[CHARLOAD] ignored malformed aggregate first=" + first
                                + " second=" + second);
                    }
                    break;
                }

                // The packet contains explicit loadout choices (newUnique) and a cached inventory
                // snapshot (curUnique). They may reuse a numeric slot in the SAME packet. Applying
                // both in a single pass overwrote the explicit choice with the stale snapshot.
                for (int ci = 0; ci < chCount; ci++) {
                    int chp = 1 + (ci * CHAR_ENTRY_SIZE);
                    int slot = buffer[chp] & 0xff;
                    if (slot < 1 || slot > CHAR_SLOT_COUNT || slot == 9) {
                        System.out.println("CharSave non-character slot " + slot + ", skipping entry");
                        continue;
                    }
                    int newUnique = (buffer[chp + 5] & 0xff)
                                  | ((buffer[chp + 6] & 0xff) << 8)
                                  | ((buffer[chp + 7] & 0xff) << 16)
                                  | ((buffer[chp + 8] & 0xff) << 24);
                    if (newUnique >= 2000000000 && !explicitSlots.contains(slot)) {
                        int charId = newUnique - 2000000000;
                        chUsers.setCharSlot(client.getUserId(), slot, charId);
                        explicitSlots.add(slot);
                        System.out.println("CharSave slot " + slot + " -> char " + charId
                                           + " (explicit newUnique=" + newUnique + ")");
                    } else if (newUnique >= 2000000000) {
                        System.out.println("CharSave duplicate explicit slot " + slot + ", keeping first choice");
                    }
                }

                // curUnique is the cached slot mapping used by 0x143A keys
                // such as red=4/blue=2. Persist that mapping for resolution,
                // but do not use char_slots to build CharInfo's installed list.
                // CharInfo derives its two installed cards only from the active
                // red/blue loadout, so this cannot mark every card Installing.
                for (int ci = 0; ci < chCount; ci++) {
                    int chp = 1 + (ci * CHAR_ENTRY_SIZE);
                    int slot = buffer[chp] & 0xff;
                    if (slot < 1 || slot > CHAR_SLOT_COUNT || slot == 9 || explicitSlots.contains(slot))
                        continue;
                    int curUnique = (buffer[chp + 1] & 0xff)
                                  | ((buffer[chp + 2] & 0xff) << 8)
                                  | ((buffer[chp + 3] & 0xff) << 16)
                                  | ((buffer[chp + 4] & 0xff) << 24);
                    if (curUnique >= 2000000000) {
                        chUsers.setCharSlot(client.getUserId(), slot, curUnique - 2000000000);
                    }
                }
            }
            // 0x1439 ack: status-ok, no body (the client's handler ignores the payload).
            ChangeCharAck changeCharAck = new ChangeCharAck();
            client.write(changeCharAck.getData(client.getSeqNum()), changeCharAck.getSize());
            // Do NOT push an unsolicited 0x1435 here.  The client treats that
            // character-info response as a fresh inventory-state transition and
            // immediately emits another 0x1438 save.  Repeating the pair created
            // a save/info feedback loop and ended with the client closing its
            // channel as "Access authority denied".  It explicitly requests
            // 0x1435 when it needs a refresh, so 0x1439 is the complete ACK.
        }else if (msgId == 0x143a) {
            // CS_IN_DEFAULTCHAR_REQ (0x143a) - the client sends this right after a CHARACTER change
            // and BLOCKS waiting for the 0x143b ack before it will leave the inventory. Without it the
            // client hangs (can't exit) - but only after a char change, since a weapon-only change
            // never sends 0x143a. Same pending-ack pattern as CHARSAVE 0x1439; a minimal status-OK
            // ack unblocks it.
            if (buffer != null && buffer.length >= 2) {
                int redSlot = buffer[0] & 0xff;
                int blueSlot = buffer[1] & 0xff;
                Users users = new Users();
                Users.CharacterLoadout current = users.getCharacterLoadout(client.getUserId());
                // 0x1438 has just stored the explicit character in these selector
                // slots. A valid *old* aggregate (often the starter characters)
                // must not mask the user's newly selected cards in 0x143A.
                int[] pendingPair = ServerConfig.CLIENT_2012
                        ? client.consumePendingCharacterLoadout() : null;
                int redSlotId = users.getCharacterIdForSelectionSlot(client.getUserId(), redSlot);
                int blueSlotId = users.getCharacterIdForSelectionSlot(client.getUserId(), blueSlot);
                int redListId = users.getCharacterIdForListIndex(client.getUserId(), redSlot);
                int blueListId = users.getCharacterIdForListIndex(client.getUserId(), blueSlot);
                int redCharId = pendingPair != null ? pendingPair[0]
                        : CharacterFaction.isRedFaction(redSlotId) ? redSlotId
                        : (!ServerConfig.CLIENT_2012 && CharacterFaction.isRedFaction(redListId) ? redListId : current.redCharId);
                int blueCharId = pendingPair != null ? pendingPair[1]
                        : CharacterFaction.isBlueFaction(blueSlotId) ? blueSlotId
                        : (!ServerConfig.CLIENT_2012 && CharacterFaction.isBlueFaction(blueListId) ? blueListId : current.blueCharId);
                if (CharacterFaction.isRedFaction(redCharId)
                        && CharacterFaction.isBlueFaction(blueCharId)) {
                    if (redCharId != current.redCharId || blueCharId != current.blueCharId)
                        users.setCharacterLoadout(client.getUserId(), redCharId, blueCharId);
                    System.out.println("[CHARSELECT] user=" + client.getUserId()
                            + " keys=" + redSlot + "/" + blueSlot
                            + (pendingPair != null ? " source=0x1438-pair" : "")
                            + " slot/list=" + redSlotId + "/" + redListId
                            + "," + blueSlotId + "/" + blueListId
                            + " -> red=" + redCharId + " blue=" + blueCharId);
                } else {
                    System.out.println("[CHARSELECT] ignored invalid 0x143A selection"
                            + " red=" + redCharId + " blue=" + blueCharId);
                }
            } else {
                System.out.println("[CHARSELECT] ignored truncated 0x143A payload");
            }
            DefaultCharAck defaultCharAck = new DefaultCharAck();
            client.write(defaultCharAck.getData(client.getSeqNum()), defaultCharAck.getSize());
        }else if (msgId == 0x1202) {
            // Keepalive / heartbeat (0x1202) - the client pings this periodically ON ITS CHANNEL CONNECTION
            // (the same wire that carried 0x1301 login and owns the 0x130D->0x1200->0x1201 dispatcher, per
            // Ghidra). We piggy-back the UDP-address bootstrap here: reply 0x130D on THIS connection (once).
            // The client's 0x130D case is unconditional -> it fires 0x1200 UDPSUCCESS_REQ back on this same
            // wire, which we answer with 0x1201 carrying 127.0.0.1:40708 -> the match destination is set.
            // (Sending 0x130D at UDPSTART failed because that arrives on the GAME connection, wrong dispatcher.)
            if (!client.isUdpKicked()) {
                client.setUdpKicked(true);
                UdpAddrAck kick = new UdpAddrAck();
                client.write(kick.getData(client.getSeqNum()), kick.getSize());
                System.out.println("[UDPBOOT] 0x1202 on channel conn from " + client.getLoginName()
                        + " -> sent 0x130D (triggers 0x1200 -> 0x1201)");
            }
        }else if (msgId == 4622) {
            // msgId 0x120E - recurring update packet, absorb silently
        }else if (msgId == 5208) {
            // msgId 0x1458 - unknown, absorb silently
        }else if (msgId == 5182) {
            // msgId 0x143E - unknown, absorb silently
        }else if (msgId == 5936) {
            // msgId 0x1730 - room list / enter lobby. NOTE: collaborator's build sends a RoomListAck
            // here, but that class isn't in this tree yet (not provided). Absorbing + logging for now
            // (matches current server behavior); wire the real ack once RoomListAck is Ghidra-verified.
            StringBuilder rlLog = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 64); i++)
                rlLog.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("[TODO RoomList] 0x1730 payload(" + buffer.length + "): " + rlLog.toString().trim());
        }else if (msgId == 5744) {
            // msgId 0x1670 - hackshield module report
            StringBuilder hsLog = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 64); i++)
                hsLog.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("[TODO Hackshield] 0x1670 payload(" + buffer.length + "): " + hsLog.toString().trim());
            // NOTE: collaborator's build replies with a HackshieldAck, but that class isn't in this
            // tree yet (not provided). Absorbing + logging for now; wire the real ack once verified.
        }else if (msgId == 0x1678) {
            // CS_FD_TUNNELING_REQ (0x1678, from the community cshell packet list) - match-start UDP tunnel
            // setup. The client BLOCKS waiting for CS_FD_TUNNELING_ACK (0x1679); no reply = the generic
            // "Network Error" (3011/3114) disconnect. Reply with TunnelingAck (echoes the request payload).
            StringBuilder tnl = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 48); i++)
                tnl.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("[TUNNELING] 0x1678 REQ payload(" + buffer.length + "): " + tnl.toString().trim());
            TunnelingAck tunnelingAck = new TunnelingAck();
            client.write(tunnelingAck.getData(client.getSeqNum()), tunnelingAck.getSize());
            System.out.println("[TUNNELING] replied 0x1679 TUNNELING_ACK (" + tunnelingAck.getSize() + "B)");
        }else if (msgId == 5144) {
            // msgId 0x1418 - unknown, absorb silently
            StringBuilder unkLog = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 64); i++)
                unkLog.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("Unknown 0x1418 payload(" + buffer.length + "): " + unkLog.toString().trim());
        }else if (msgId == 6668) {
            // msgId 0x1A0C - unknown, absorb silently
            StringBuilder unkLog = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 64); i++)
                unkLog.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("Unknown 0x1A0C payload(" + buffer.length + "): " + unkLog.toString().trim());
        }else if (msgId == 4920) {
            // msgId 0x1338 - unknown, absorb silently
            StringBuilder unkLog = new StringBuilder();
            for(int i = 0; i < Math.min(buffer.length, 64); i++)
                unkLog.append(String.format("%02x ", buffer[i] & 0xff));
            System.out.println("Unknown 0x1338 payload(" + buffer.length + "): " + unkLog.toString().trim());
        }else if (msgId == 4618) {
            // msgId 0x120A - periodic update/keepalive, absorb silently
        }else if (msgId == 4638) {
            // msgId 0x121E - periodic status/keepalive (most frequent undefined msg), absorb silently
        }else if (msgId == 5164) {
            // msgId 0x142C - unknown periodic, absorb silently
        }else if (msgId == 5746) {
            // msgId 0x1672 - in-game periodic update, absorb silently
        }else {
            System.out.println("Undefined Msg Recieved " + msgId);
        }
        
        
    }

    private static int readLittleEndianInt(byte[] buffer, int offset)
    {
        if (buffer == null || offset < 0 || offset + 4 > buffer.length)
            return 0;
        return (buffer[offset] & 0xff)
             | ((buffer[offset + 1] & 0xff) << 8)
             | ((buffer[offset + 2] & 0xff) << 16)
             | ((buffer[offset + 3] & 0xff) << 24);
    }
    
    private DatagramSocket udpSocket;
    private DatagramSocket udpSocket2;   // bot-peer endpoint (port+1)
    private DatagramSocket relaySocket;  // ATLAS: transparent p2p relay endpoint
    public static final int RELAY_PORT = 40710;   // dedicated relay port (distinct from 40708 relay + 40709 bot)
    public static int RELAY_IP = 0x0100007F;      // server's reachable IP (network-order-as-int); auto-detected

    /** Find the server's Hamachi (25.x.x.x) interface IP as a network-order-as-int (for the conn-block).
     *  Falls back to loopback if none found. Advertised to clients as the peer endpoint. */
    private static int detectRelayIp() {
        return ServerConfig.ADVERTISED_IP;
    }

    // ===== Per-player UDP relay: one dedicated port per human so every peer presents a DISTINCT
    // source endpoint to each client. A shared relay port collapses all peers into one connMgr entry
    // (connected-UDP demuxes by endpoint) => only one peer renders at N>=3. Wire format is unchanged;
    // only the conn-block PORT value varies per peer, so the working 2-player/bot paths cannot regress. =====
    public static GameServer INSTANCE;                     // single match server (set in ctor)
    // PEER_ENDPOINT_MODE — how the TWO conn-block address pairs are filled for a real peer. The client
    // engine tries BOTH pairs (WON/LithTech external+internal scheme): global pair (+0x6c/+0x9a) and
    // local pair (+0x94/+0x34). All three modes give each peer a DISTINCT identity => no N>=3 collision.
    //   0 RELAY_BOTH  : both pairs = per-player relay port. Works behind NAT; +1 server hop of latency.
    //   1 DIRECT_BOTH : both pairs = peer's own observed endpoint. Lowest latency, simplest; needs mutual
    //                   reachability (Hamachi/LAN). Safest unblock.
    //   2 HYBRID      : global = relay (always-reachable fallback), local = peer's direct endpoint (fast
    //                   path). Uses BOTH mechanisms at once -- the most faithful to the original scheme.
    public static int PEER_ENDPOINT_MODE = 1;   // DIRECT_BOTH (start here to unblock; try 2 = HYBRID next)

    // BOT_SEAT_MODE — how AI bots are seated. LIVE FINDING (monke): the AI body RENDERS off the 0x1675
    // (AddAISlotAck) path, NOT the roster. Roster-seating makes the client treat the bot as a remote PLAYER
    // (waits for a p2p peer it never fully gets) -> invisible shell. So 0x1675 is LOAD-BEARING for render and
    // cannot be dropped. The duplicate is two objects (0x1675 AI object + the spawnBots field body) that both
    // must exist for render, so the merge has to happen client-side (capture pending) OR by removing the
    // redundant second object on the spawn side (see BOT_SPAWN_MODE).
    //   0 AI_ONLY       : 0x1675 + spawnBots. RENDERS the AI. Has the 2-row duplicate. <-- DEFAULT (visible).
    //   1 AI_PLUS_ROSTER: roster (0x1605) + 0x1675. Two/three objects -> duplicate (tested).
    //   2 ROSTER_ONLY   : roster only, no 0x1675. INVISIBLE / renders as a player shell (tested, WRONG). Kept
    //                     only for reference - do not use for a normal AI bot.
    // Does NOT touch real players / multiplayer - only the bot-add path.
    public static int BOT_SEAT_MODE = 0;   // AI_ONLY: 0x1675 per bot, NO roster re-broadcast (the flow the
                                           // multi-bot spawn fix was built for). Was 1 (roster) - added during
                                           // the color hunt, which turned out to be the WolfHunt mode issue,
                                           // so the roster is likely both unnecessary now AND the thing that
                                           // re-collapsed multi-bot bodies. Flip back to 1 only if color breaks.
    // BOT_SPAWN_MODE — how a bot is spawned at round start (replaces the old SUPPRESS_SPAWNACK / SKIP_SPAWNBOTS
    // flags). Live findings (monke): 0x1675 handles the in-field body + puts the bot on screen with 1 join
    // message but NO scoreboard row; the scoreboard row comes from the ChangeSlotStatus promotions; SpawnAck is
    // a no-op for bots. We send TWO ChangeSlotStatus (status 4 then 3) -> TWO scoreboard rows = the duplicate.
    //   0 FULL      : status 4 -> SpawnAck -> status 3 (mirrors a real player). Two status msgs -> 2 rows (the bug).
    //   1 LIVE_ONLY : ONE status-3 promotion only (no status-4, no SpawnAck). Testing: one status -> ONE row.
    //                 <-- DEFAULT (the fix candidate). If the bot doesn't render/appear on TAB, fall back to 0.
    //   2 NONE      : skip all spawn treatment. 0x1675-only: in-field body but NO scoreboard row (monke saw this).
    public static int BOT_SPAWN_MODE = 1;
    // BOT_SEND_ENTERGAME — send EnterGameAck (0x161d) for the bot at spawn. That message carries the team
    // byte into FUN_3428bb20 (the red/blue color setter); we had dropped it, so the bot got no team -> the
    // client defaulted it to red and derived an odd (red) slot. Instance 3 proved dropping it did NOT affect
    // the duplicate, so adding it back should fix team+color+slot WITHOUT re-doubling. If the double returns,
    // set this false. Carries the server's roomTeam (1=blue/2=red), exactly like a real player's EnterGameAck.
    public static boolean BOT_SEND_ENTERGAME = true;
    // BOT_FORCE_CHAR — test lever for the "team/form comes from the CHARACTER" hypothesis (monke + Instance 3).
    // -1 = off (spawn char = teamBasic 30005 blue / 30006 red by roomTeam). >=0 = force ALL bots to spawn this
    // char id, AND send the SpawnAck that carries it even in LIVE_ONLY. Chars are faction-locked (+8) and
    // form-locked (+6), so if this controls the bot, color/form follow the id:
    //   30005 -> should render BLUE (blue basic)      30006 -> RED (red basic)
    //   30001/30002 -> blue wolf-capable              30003/30004 -> red wolf-capable
    // If forcing 30005 turns a bot BLUE, the character is the team/form channel and that's the real fix.
    public static int BOT_FORCE_CHAR = -1;
    // BOT_FORCE_SLOT — test lever for "does the client honor the server's slot, or auto-fill autonomously?"
    // -1 = off (normal parity seating). >=0 = seat the next bot at this absolute slot. Force an EVEN slot
    // (e.g. 4): appears there + BLUE => server controls slot, parity=color, fix = seat parity per team.
    // Appears elsewhere / still red => client ignores our slot and fills its own => we mirror its fill order.
    public static int BOT_FORCE_SLOT = -1;
    // BOT_COMPENSATE_PLUS1 — the client's 0x1675 AI-slot handler places the AI at wire_slot + 1 (confirmed
    // live: server slot 2 -> client 3; forced 4 -> client 5; both +1). Color = client-slot parity, so our
    // even/blue server slots land on odd/red after the +1 -> bots always render red. true = flip the seating
    // parity so the +1 lands the intended color (blue -> odd server slot -> client even -> BLUE). Real players
    // are unaffected (no +1 on their path). Set false to disable if the client offset changes.
    // Team is carried explicitly by 0x1675/+4 and the normal room packets.
    // Do not derive it from slot parity; that created sparse/overlapping UI
    // slots and destabilised the client after several bots were added.
    // The AI widget renders its seat at serverSlot+1. Keep the physical
    // roster parity aligned with the selected panel: team1/blue uses odd
    // server slots -> even client seats; team2/red uses even -> odd.
    public static boolean BOT_COMPENSATE_PLUS1 = true;
    // BOT_FORCE_TEAM — the request never carries a team (buffer[2] is a constant 1, Wolf), so serverTeam is
    // always 1 -> every bot same team -> same color. This lets us MAKE the team vary so we can map team->color
    // directly. -1 = off (normal). >=0 = force the next bot's serverTeam to this raw value. Sweep it:
    //   try 0, 1, 2 and note the color of each. Whichever value renders BLUE is the client's blue convention;
    //   the fix is then to ASSIGN the team server-side (request can't) and emit that value for blue.
    // (Char and slot are already eliminated as color levers, so any color change here is the TEAM byte.)
    public static int BOT_FORCE_TEAM = -1;
    // BOT_FORCE_AIDM_MODE — when an AI bot is added, force the room's mode to AI-Deathmatch (base DM 0x02 +
    // AI flag 0x040000). Guards against the room being (or being changed to) WolfHunt 0x80, which has no
    // teams, needs 4 players, and hardcodes AI to the enemy side. true = force it. Set false to observe the
    // raw client-sent mode via the [MODE] logs.
    public static boolean BOT_FORCE_AIDM_MODE = true;
    // BOT_FILL_CONTIGUOUS — seat bots at the lowest free slot up to capacity (contiguous), instead of the
    // old one-parity fill. Fixes the parity-gap "skip" and the past-capacity crash now that the room is real
    // AI-Deathmatch (teams fill by AI-DM rules, not a slot-parity trick). false = legacy parity seating.
    // Real room slots must remain contiguous and unique.  Team selection is
    // represented by the explicit team field, not a slot-parity convention.
    public static boolean BOT_FILL_CONTIGUOUS = false;
    // Character faction lookup: chars are faction-locked at spawn (blue = team 1, red = team 2).
    // Centralized in CharacterFaction.java so RoomCharsAck and GameServer share one truth.
    private static final java.util.Set<Integer> RED_CHARS  = wtserver.server.CharacterFaction.RED_CHARS;
    private static final java.util.Set<Integer> BLUE_CHARS = wtserver.server.CharacterFaction.BLUE_CHARS;
    public static final int RELAY_PORT_BASE = 41000;       // 41000..41499 (clear of 40708/40709/40710)
    private final java.util.Map<Integer, DatagramSocket> relaySockByPort = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger relaySeq = new java.util.concurrent.atomic.AtomicInteger(0);
    private long relayFwd = 0, relayDrop = 0;              // rate-limited forward/drop counters (diagnostics)

    /** Lazily allocate a dedicated relay port + listener for a human client; cached on the Client. */
    public synchronized int relayPortFor(Client c) {
        if (c == null || c.isBot()) return RELAY_PORT;
        if (c.getRelayPort() != 0)  return c.getRelayPort();
        int port = RELAY_PORT_BASE + (relaySeq.getAndIncrement() % 500);
        try {
            DatagramSocket s = new DatagramSocket(port, ServerConfig.bindAddress());
            relaySockByPort.put(port, s);
            c.setRelayPort(port);
            final DatagramSocket fs = s; final int fp = port;
            Thread t = new Thread(() -> perPlayerRelayLoop(fs, fp), "relay-" + port);
            t.setDaemon(true); t.start();
            Log.match("[RELAY] " + c.getLoginName() + " -> dedicated relay port " + port);
            return port;
        } catch (Exception e) {
            Log.match("[RELAY] bind " + port + " failed (" + e + "); shared fallback " + RELAY_PORT);
            return RELAY_PORT;
        }
    }

    /** Fill the two conn-block address pairs for a real peer per PEER_ENDPOINT_MODE.
     *  Returns {globalIp, globalPort, localIp, localPort}; ports are unsigned shorts widened to int.
     *  Callers write: addInteger(r[0]); addShort(htons(r[1])); addInteger(r[2]); addShort(htons(r[3])). */
    public int[] peerConnBlock(Client c) {
        int dIp = c.getUdpIp(), dPort = c.getUdpPort() & 0xffff;   // peer's observed (direct) endpoint
        switch (PEER_ENDPOINT_MODE) {
            case 1:   // DIRECT_BOTH
                return new int[]{ dIp, dPort, dIp, dPort };
            case 2: { // HYBRID: global=relay, local=direct
                int rp = relayPortFor(c);
                return new int[]{ RELAY_IP, rp, dIp, dPort };
            }
            default: { // 0 RELAY_BOTH
                int rp = relayPortFor(c);
                return new int[]{ RELAY_IP, rp, RELAY_IP, rp };
            }
        }
    }

    private void perPlayerRelayLoop(DatagramSocket sock, int myPort) {
        byte[] buf = new byte[2048];
        while (!sock.isClosed()) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try { sock.receive(p); forwardPerPlayer(p, myPort); }
            catch (Exception e) { if (sock.isClosed()) return; }
        }
    }

    /** Packet arrived on target Y's socket (myPort). Sender X = client matching the source addr.
     *  Resend the payload FROM X's socket to Y's observed endpoint, so Y's engine sees it arrive from
     *  RELAY_IP:<X's port> = exactly Y's conn-block endpoint for X. Generalizes to any N. */
    private void forwardPerPlayer(DatagramPacket p, int targetPort) {
        byte[] sb = p.getAddress().getAddress(); if (sb.length != 4) return;
        int srcIp   = (sb[0]&0xff)|((sb[1]&0xff)<<8)|((sb[2]&0xff)<<16)|((sb[3]&0xff)<<24);
        int srcPort = p.getPort() & 0xffff;
        Client target = null, sender = null;
        for (Client c : getClientList()) {
            if (c == null || c.isBot()) continue;
            if (c.getRelayPort() == targetPort) target = c;
            if ((c.getUdpPort()&0xffff)==srcPort && c.getUdpIp()==srcIp) sender = c;
        }
        // Fallback: the client sends peer traffic from a per-connection socket whose source PORT differs
        // from the type-2-registered port (distinct endpoints => the engine opens a socket per peer). The
        // exact (ip:port) match then misses. Identify the sender by source IP among the target's room
        // members instead -- unambiguous on Hamachi/LAN where each client has its own IP. Refuse to guess
        // if two members share an IP (same box). Only runs when the exact match already failed.
        if (sender == null && target != null) {
            Room r = getRoomWithNumber(target.getRoomNumber());
            if (r != null) {
                Client byIp = null; int hits = 0;
                for (Client c : r.getUsers()) {
                    if (c == null || c.isBot() || c == target) continue;
                    if (c.getUdpIp() == srcIp) { byIp = c; hits++; }
                }
                if (hits == 1) sender = byIp;
            }
        }
        if (target == null || sender == null || sender == target) {
            if ((relayDrop++ % 200) == 0)
                Log.match("[RELAY] drop on :" + targetPort + " src=" + p.getAddress().getHostAddress()
                        + ":" + srcPort + " target=" + (target==null? "?" : target.getLoginName())
                        + " sender=" + (sender==null? "? (source not matched to any client's observed endpoint)"
                                                     : sender.getLoginName()));
            return;
        }
        DatagramSocket sSock = relaySockByPort.get(sender.getRelayPort());
        if (sSock == null) return;
        int tip = target.getUdpIp(); int tport = target.getUdpPort() & 0xffff;
        if (tport == 0) return;
        try {
            java.net.InetAddress taddr = java.net.InetAddress.getByAddress(new byte[]{
                (byte)(tip&0xff),(byte)((tip>>8)&0xff),(byte)((tip>>16)&0xff),(byte)((tip>>24)&0xff)});
            byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
            sSock.send(new DatagramPacket(data, data.length, taddr, tport));   // FROM sender's socket
            if ((relayFwd++ % 500) == 0)
                Log.match("[RELAY] fwd " + sender.getLoginName() + " -> " + target.getLoginName()
                        + " (" + data.length + "B) in :" + targetPort + " out src :" + sender.getRelayPort()
                        + " -> " + target.getUdpIp() + ":" + tport);
        } catch (Exception e) { }
    }

    /** Optional: call on room-leave / disconnect to free the port. */
    public synchronized void releaseRelayPort(Client c) {
        if (c == null || c.getRelayPort() == 0) return;
        DatagramSocket s = relaySockByPort.remove(c.getRelayPort());
        if (s != null) s.close();
        c.setRelayPort(0);
    }

    /** Relay receive loop: forward each inbound p2p datagram to the OTHER player in the sender's room. */
    private void relayLoop() {
        byte[] buf = new byte[2048];
        while (true) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try { relaySocket.receive(p); forwardRelay(p); }
            catch (Exception e) { }
        }
    }

    /** Identify the sender by observed endpoint, then forward the datagram opaquely to the other room member(s). */
    private void forwardRelay(DatagramPacket p) {
        byte[] sb = p.getAddress().getAddress();
        if (sb.length != 4) return;
        int srcIp = (sb[0]&0xff) | ((sb[1]&0xff)<<8) | ((sb[2]&0xff)<<16) | ((sb[3]&0xff)<<24);
        int srcPort = p.getPort() & 0xffff;
        Client sender = null;
        for (Client c : getClientList()) {
            if (c == null || c.isBot()) continue;
            if ((c.getUdpPort() & 0xffff) == srcPort && c.getUdpIp() == srcIp) { sender = c; break; }
        }
        if (sender == null) return;                                   // unknown source (not a known client)
        Room room = getRoomWithNumber(sender.getRoomNumber());
        if (room == null) return;
        byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
        for (Client other : room.getUsers()) {
            if (other == null || other == sender || other.isBot()) continue;
            int oip = other.getUdpIp(); int oport = other.getUdpPort() & 0xffff;
            if (oport == 0) continue;
            try {
                java.net.InetAddress oaddr = java.net.InetAddress.getByAddress(new byte[]{
                    (byte)(oip&0xff), (byte)((oip>>8)&0xff), (byte)((oip>>16)&0xff), (byte)((oip>>24)&0xff)});
                relaySocket.send(new DatagramPacket(data, data.length, oaddr, oport));   // FROM relaySocket (RELAY_PORT)
            } catch (Exception e) { }
        }
    }
    private long matchPktCount = 0;   // rate-limit the CONNECTED match-packet log
    private LTMatchCrypto matchCrypto;   // LithTech connected-UDP Blowfish (loaded from bf_schedule.bin)
    private int ourConnectSent = 0;   // reverse-direction (peer-initiated) CONNECTs sent
    private boolean reverseUp = false;   // client ACCEPTed our reverse CONNECT

    private void processUdp(DatagramPacket packet, DatagramSocket replySock)
    {
        byte msg[] = packet.getData();
        // ---- UDP DIAGNOSTIC LOGGING ----
        // Dump the raw datagram + the session key the client sent, and compare it against every
        // stored TCP client's session key, so we can see EXACTLY why getClient() misses.
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < msg.length; i++) raw.append(String.format("%02x ", msg[i] & 0xff));
        String _src = packet.getAddress().getHostAddress() + ":" + packet.getPort();
        Log.udp("[UDP] raw(" + msg.length + ") from " + _src + " = " + raw.toString().trim()
                + "  (msg[0]=" + (msg.length > 0 ? (msg[0] & 0xff) : -1) + ")");

        // ---- LithTech Titan UNCONNECTED handshake (the real match-UDP gate) ----
        // The engine's match UDP is the LithTech connectionless handshake: [uint32 TOKEN][3-bit sub][payload],
        // a bitstream packed LSB-first (see LTNet, ported from ltjs udpdriver.cpp). The client's CC FF 00 00...
        // packet is TOKEN=0x0000FFCC + sub=0 (QUERY). A verbatim echo keeps sub=0 (a *request*), so the client
        // never sees a *_RESPONSE and never advances its connection state machine -> C114 timeout. Reply with
        // the matching *_RESPONSE. CONNECT_RESPONSE(accept) is the state gate that flips it to "connected".
        if (LTNet.isUnconnected(msg))
        {
            LTNet.Reader r = new LTNet.Reader(msg);
            r.seekTo(32);                                  // skip the 32-bit token
            int sub = (int) r.readBits(LTNet.MSG_BITS);
            byte[] resp = null;
            switch (sub) {
                case LTNet.MSG_QUERY:
                    resp = LTNet.queryResponse("WolfTeam", 0, false, 0);
                    Log.ltnet("[LTNET] QUERY (sub=0) from " + _src + " -> QUERY_RESPONSE");
                    break;
                case LTNet.MSG_CONNECT:
                    resp = LTNet.connectResponse(true, false);
                    Log.ltnet("[LTNET] CONNECT (sub=2) from " + _src + " -> CONNECT_RESPONSE (accept)");
                    break;
                case LTNet.MSG_PING:
                    long ping = r.readBits(LTNet.PING_BITS);
                    resp = LTNet.pingResponse(ping);
                    Log.ltnet("[LTNET] PING (sub=4) from " + _src + " -> PING_RESPONSE");
                    break;
                default:
                    Log.ltnet("[LTNET] unconnected sub=" + sub + " from " + _src + " (no handler)");
            }
            if (resp != null) {
                try {
                    replySock.send(new DatagramPacket(resp, resp.length, packet.getAddress(), packet.getPort()));
                    StringBuilder rb = new StringBuilder();
                    for (byte b : resp) rb.append(String.format("%02x ", b & 0xff));
                    Log.ltnet("[LTNET] reply(" + resp.length + ")=" + rb.toString().trim() + " -> " + _src);
                } catch (Exception e) { Log.ltnet("[LTNET] reply err: " + e); }
            }
            return;
        }

        if(msg[0] != (byte)0xCC && msg[0] != 2)
        {
            // LithTech CONNECTED-UDP: packet = [Blowfish(8B header)][plaintext 8B payload]. Decrypt the header;
            // if it's a CONNECT (type field header[4]&0x3F == 0), reply with the CONNECT-ACCEPT (type=1) the
            // driver waits for (Ghidra validate @0x1A87AEE8). That completes the match-UDP session -> the client
            // can measure ms and load in. Fingerprint = header[0] = XOR(header[1..7]) ^ 0x4C.
            matchPktCount++;
            if (matchCrypto != null && msg.length == 16) {
                byte[] hdr = matchCrypto.decryptHeader(msg);
                if (LTMatchCrypto.fingerprintOk(hdr)) {
                    int type = hdr[4] & 0x3F, sub = hdr[6] & 0xff, sess = LTMatchCrypto.session(hdr);
                    if (LTMatchCrypto.isConnect(hdr)) {                     // client CONNECT -> ACCEPT (+ reverse CONNECT)
                        try {
                            byte[] accept = matchCrypto.buildAcceptFromConnect(msg);
                            replySock.send(new DatagramPacket(accept, accept.length, packet.getAddress(), packet.getPort()));
                            // (reverse-direction CONNECT was ignored by the client and isn't the gate - the ms
                            //  ping is. Left disabled; re-enable if Ghidra shows bidirectional is needed.)
                            if (matchPktCount <= 6 || matchPktCount % 200 == 0)
                                Log.match("[MATCH] CONNECT session=0x" + Integer.toHexString(sess)
                                        + " from " + _src + " -> sent ACCEPT");
                        } catch (Exception e) { Log.match("[MATCH] accept/connect err: " + e); }
                    } else if (type == LTMatchCrypto.TYPE_ACCEPT && sub == 0x08) {   // client's ACCEPT to OUR connect
                        if (!reverseUp) {
                            reverseUp = true;
                            Log.match("[MATCH] <- client ACCEPT to our CONNECT (session=0x" + Integer.toHexString(sess)
                                    + ") - REVERSE DIRECTION UP (bidirectional established)");
                        }
                    } else {                                                // type=1 sub=0x0c -> PING (ms probe)
                        try {
                            // Send a real PONG: flip subtype ping->pong + echo the timestamp so the client can
                            // compute ms. Verbatim echo failed (reads as the peer pinging us, not a response).
                            byte[] pong = matchCrypto.buildPongFromPing(msg, LTMatchCrypto.SUB_PONG);
                            replySock.send(new DatagramPacket(pong, pong.length, packet.getAddress(), packet.getPort()));
                        } catch (Exception e) { Log.match("[MATCH] pong err: " + e); }
                        if (matchPktCount <= 40 || matchPktCount % 100 == 0) {
                            StringBuilder hx = new StringBuilder();
                            for (byte b : hdr) hx.append(String.format("%02x ", b & 0xff));
                            Log.match("[MATCH] PING sub[6]=0x" + Integer.toHexString(sub) + " session=0x" + Integer.toHexString(sess)
                                    + " hdr=" + hx.toString().trim() + " from " + _src + " -> PONG (sub=0x" + Integer.toHexString(LTMatchCrypto.SUB_PONG) + ", ts echoed)");
                        }
                    }
                    return;
                }
                // header didn't validate (not our match session) -> fall through to reflect
            }
            // fallback (crypto not loaded, or bad fingerprint): reflect as before, rate-limited
            if (matchPktCount == 1 || matchPktCount % 200 == 0)
                Log.udp("[UDP] match pkt (no-crypto/bad-fp) #" + matchPktCount + " from " + _src + " -> reflect");
            try {
                replySock.send(new DatagramPacket(msg, msg.length, packet.getAddress(), packet.getPort()));
            } catch (Exception e) { Log.udp("[UDP] reflect err: " + e); }
        }

        if(msg[0] == (byte)0xCC && msg.length == 16)
        {
            try {
                DatagramPacket echo = new DatagramPacket(msg, msg.length, packet.getAddress(), packet.getPort());
                replySock.send(echo);
                Log.udp("[UDP] echoed 0xCC (" + msg.length + "B) -> " + _src);
            } catch (Exception e) { Log.udp("[UDP] echo err: " + e); }
            return;
        }

        if(msg[0] == 2 && msg.length == 16)
        {
            // ---- TYPE-2 UDP REGISTER: reply on UDP to the SOURCE (this is the WSAEWOULDBLOCK fix) ----
            // The engine's recvfrom() blocks on the socket that sent this (e.g. 127.0.0.1:53521). We store
            // the endpoint + TCP-ack it via UdpAddr() below, but the engine ALSO needs a UDP datagram back
            // to complete its match-UDP handshake - that reply was never sent (only the TCP 0x130d went out),
            // so recvfrom stayed WSAEWOULDBLOCK and the client retransmitted type-2 + looped 0x1618. Mirror
            // the proven 0xCC echo: reflect the 16 bytes verbatim to packet source. Sent FIRST (before the
            // parse/match) so the client unblocks even if matching hiccups. If Ghidra's recvfrom capture shows
            // the parser wants a transformed reply (changed type byte / server endpoint / status), refine here.
            try {
                byte[] regAck = java.util.Arrays.copyOf(msg, msg.length);
                DatagramPacket reg = new DatagramPacket(regAck, regAck.length, packet.getAddress(), packet.getPort());
                replySock.send(reg);
                Log.udp("[UDP] type-2 ACK (echo " + regAck.length + "B) -> " + _src);
            } catch (Exception e) { Log.udp("[UDP] type-2 reply err: " + e); }

            byte session_key[] = new byte [6];
            for(int i = 0; i < 6; i++)
            {
                session_key[i] = msg[i + 4];
            }
            // Sec 293 ROOT FIX: the client's SELF-REPORTED udpIp (msg[10:14]) is garbage for a real engine
            // client - it's a slice of the session token, decoding to e.g. 0x392B2A19 = 57.43.42.25, an IP
            // that isn't the host at all. Handing that to the joiner as the peer endpoint made its P2P
            // packets fly into the void -> no reply -> +0x50 never promotes -> peer (and bots) invisible.
            // Use the OBSERVED SOURCE of this UDP packet = the client's REAL reachable endpoint instead.
            int reportedIp   = (msg[10] & 0xff) + (msg[11] & 0xff)*0x100 + (msg[12] & 0xff)*0x10000 + (msg[13] & 0xff)*0x1000000;
            short reportedPort = (short) ((msg[14] & 0xff) + (msg[15] & 0xff)*0x100);
            byte[] _srcB = packet.getAddress().getAddress();
            int udpIp = (_srcB[0] & 0xff) + (_srcB[1] & 0xff)*0x100 + (_srcB[2] & 0xff)*0x10000 + (_srcB[3] & 0xff)*0x1000000;
            short udpPort = (short) packet.getPort();
            Log.udp("[UDP] type-2 self-reported ip=" + Integer.toHexString(reportedIp) + ":" + (reportedPort & 0xffff)
                    + "  |  OBSERVED src=" + packet.getAddress().getHostAddress() + ":" + packet.getPort() + " (using observed)");

            // Show the session key from the UDP packet + every stored client's key for comparison.
            StringBuilder uk = new StringBuilder();
            for (byte x : session_key) uk.append(String.format("%02x ", x & 0xff));
            Log.udp("[UDP] packet session_key(6)=" + uk.toString().trim());

            // The LAST 4 bytes of the UDP session key (msg[6..9]) = the u32 we put at 0x1301 payload
            // [3:7], which we set to the user_id. (The FIRST 2 bytes are computed client-side from
            // something other than the ack, so we match on the 4 bytes we control.)
            int udpUserId = (msg[6] & 0xff) + (msg[7] & 0xff)*0x100 + (msg[8] & 0xff)*0x10000 + (msg[9] & 0xff)*0x1000000;
            Log.udp("[UDP] last4 as user_id=" + udpUserId);

            java.util.ArrayList<Client> all = getClientList();
            Log.udp("[UDP] comparing against " + all.size() + " connected client(s):");
            Client c = null;
            for (Client cc : all) {
                byte[] sk = cc.getSessionKey();
                StringBuilder sks = new StringBuilder();
                if (sk != null) for (byte x : sk) sks.append(String.format("%02x ", x & 0xff));
                System.out.println("        client '" + cc.getLoginName() + "' userId=" + cc.getUserId()
                        + " stored key=" + sks.toString().trim());
                if (cc.getUserId() == udpUserId) c = cc;   // match on the controlled 4 bytes (user_id)
            }

            if(c != null)
            {
                Log.udp("Client found " + c.getLoginName());
                short oldPort = c.getUdpPort();
                int   oldIp   = c.getUdpIp();
                c.UdpAddr(udpIp, udpPort);
                // ATLAS (capture-first fix): the join-time roster is broadcast BEFORE this client's
                // match-UDP endpoint is observed, so existing members (esp. the HOST) hold a stale/
                // invalid endpoint for the joiner and never arm p2p. Confirmed live: as a JOINER the
                // client sends p2p (endpoint known), but as a HOST it sends nothing (joiner endpoint
                // stale). Re-broadcast the roster now that the real endpoint is captured so every
                // member gets it and the host's connect (FUN_3418e680) fires for the joiner.
                if (oldPort != (short) udpPort || oldIp != udpIp) {
                    Room r = getRoomWithNumber(c.getRoomNumber());
                    if (r != null) {
                        RoomCharsAck reAck = new RoomCharsAck();
                        r.write(reAck);
                        Log.udp("[UDP] roster re-broadcast after " + c.getLoginName()
                                + " UDP endpoint captured -> peers can now p2p to it");
                    }
                }
            }else {
                System.out.println("Client not found");
            }
        }
    }
    
    public synchronized void writeToChannel(ServerMsg msg)
    {
        ArrayList<Client> clientList = getClientList();
        for (int i = 0; i < clientList.size(); i++) {
            try {
                Client c = clientList.get(i);
                if (c != null) {
                    if(c.getRoomNumber() == -1)
                        c.write(msg.getData(c.getSeqNum()), msg.getSize());
                }
            } catch (Exception ex) {

            }
        }
    }
    
    public synchronized Room createRoom(CreateAck c)
    {
        short n = 0;
        for(int i = n; i < 1000; i++)
        {
            boolean exists = false;
            for(int j = 0; j < rooms.size(); j++)
            {
                Room r = rooms.get(j);
                if(r.getRoomNumber() == i)
                {
                    exists = true;
                    break;
                }
            }
            if(!exists)
            {
                n = (short) i;
                break;
            }
        }
        //n = (short) (n + 1);
        System.out.println("Creating Room " + n + " Mode " + c.getMode() + " (0x" + Integer.toHexString(c.getMode())
                + ") Wins " + c.getWins() + " Time " + c.getTime());
        Room newRoom = new Room(c.getRoomTitle(), c.getRoomPassword(), n, c.getCapacity(), c.getMap(), c.getMode(), c.getWins(), c.getPoints(), c.getTime());
        rooms.add(newRoom);
        System.out.println("[MODE] room " + n + " -> DeathMatch=" + newRoom.isDeathMatch()
                + " AiDeathMatch=" + newRoom.isAiDeathMatch() + " Wolfhunt=" + newRoom.isWolfhunt()
                + " | lowByte=0x" + Integer.toHexString(newRoom.getMode() & 0xFF)
                + " (0x02=DM 0x80=WolfHunt) AIflag=" + ((newRoom.getMode() & 0x040000) != 0));
        if (newRoom.isAiDeathMatch())
            System.out.println("[AI] AI-Deathmatch room created (mode=0x" + Integer.toHexString(newRoom.getMode())
                    + ") - expecting client ADD_AISLOT fill");
        return newRoom;
    }
    
    public synchronized Room getRoomWithNumber(short n)
    {
        for(int i = 0; i < rooms.size(); i++)
        {
            if(rooms.get(i).getRoomNumber() == n)
                return rooms.get(i);
        }
        return null;
    }
    
    public class Room 
    {
        String title;
        String password;
        short roomNumber;
        byte roomCapacity;
        byte nUsers;
        boolean isOpen;
        boolean gameStarted;
        boolean roundStarted;
        Client users[];
        byte roomMaster;
        byte map, wins, points;
        int mode;   // full 4-byte mode (AI-DM = 0x00040202); low byte = base mode
        short time;
        short redScore, blueScore;
        long roundTime, plantTime;
        ArrayList<Client> deadList;
        java.util.HashSet<Integer> aiSlots = new java.util.HashSet<Integer>();   // absolute slots held by AI
        /**
         * Pick the next EMPTY absolute slot for an AI bot.
         *
         * Binary/Ghidra (CONFIRMED): 0x1675 payload[0] is the ABSOLUTE slot index (0-17), NOT a status byte
         * (stored -> conn+0x1B6, read as getPlayerByIndex). Placement is occupancy-gated client-side: an
         * already-held slot is rejected as "access denied". So we must hand back a slot the CLIENT currently
         * sees as empty - and the client's occupancy is exactly what we told it in the roster (RoomCharsAck):
         * that ack sends each real player's slot as its index in getUsers() (0..N-1, compacted list), NOT
         * getRoomSlot() and NOT any team parity. Team is a SEPARATE field ([+3] of the record) and does not
         * drive slot selection - there is no team->slot mapping (the old 2*teamSlot+parity guess mislabelled
         * the occupied slot and dropped the bot onto a real player). clientTeam is kept only for signature
         * parity; the caller passes team straight into the ack's team field.
         */
        public synchronized byte nextFreeSlot(int clientTeam) {
            int n = Math.max(18, roomCapacity);
            boolean[] used = new boolean[n];
            // Real players occupy the SAME absolute slots the roster (RoomCharsAck) sent the client:
            // their index in getUsers() (0..size-1, compacted). Mark those so a bot never lands on one.
            ArrayList<Client> roster = getUsers();
            for (int i = 0; i < roster.size() && i < n; i++) used[i] = true;
            // AI bots already placed this session (no server-side Client object exists for them):
            for (int s : aiSlots) if (s >= 0 && s < n) used[s] = true;
            // Lowest empty absolute slot - the client fills AI slots in ascending order:
            for (int s = 0; s < n; s++) {
                if (!used[s]) { aiSlots.add(s); return (byte) s; }
            }
            return (byte) 0;
        }
        byte flags[] = new byte [5];
        byte roundNumber;
        
        public Room (String title, String password, short roomNumber, byte roomCapacity, byte map, int mode, byte wins, byte points, short time)
        {
            gameStarted = false;
            this.title = title;
            this.password = password;
            this.roomNumber = roomNumber;
            this.roomCapacity = roomCapacity;
            this.map = map;
            this.mode = mode;
            this.wins = wins;
            this.points = points;
            this.time = time;
            //users = new ArrayList<Client>();
            users = new Client[17];
            for(int i = 0; i < 17; i++)
                users[i] = null;
            roomMaster = 0;
            nUsers = 0;
            deadList = new ArrayList<Client>();
            deadList.clear();
            isOpen = true;
            roundStarted = false;
            
        }
        
        public void process()
        {
            //Process Timeout
            long currentTime = (System.currentTimeMillis() - roundTime) / 1000;
            if(currentTime > time && roundStarted && gameStarted)
            {
                if (isDeathMatch()) {
                    if (redScore >= blueScore) {
                        endRound((byte) 1, true);
                    }else endRound((byte) 2, true);
                }
                
                if (isIceHold() || isDestruction()) {
                    boolean bEndGame = false;    
                    int r = getTeamDeadCount((byte)1);
                        int b = getTeamDeadCount((byte)2);
                        if(r <= b)
                            redScore++;
                        else blueScore++;
                        if (redScore >= wins || blueScore >= wins) {
                            bEndGame = true;
                        if (redScore >= blueScore) {
                            endRound((byte) 1, bEndGame);
                        }else endRound((byte) 2, bEndGame);
                    }
                }
                if(isConquest())
                {
                    boolean bEndGame = false;
                    int r = getFlagCount((byte) 1);
                    int b = getFlagCount((byte)2);
                         if(r <= b)
                            redScore++;
                        else blueScore++;
                        if (redScore >= wins || blueScore >= wins) {
                            bEndGame = true;
                        if (redScore >= blueScore) {
                            endRound((byte) 1, bEndGame);
                        }else endRound((byte) 2, bEndGame);
                       }
                }
                //endRound((byte)1, true);
            }
            long bombTime = (System.currentTimeMillis() - plantTime) / 1000;
            //System.out.println("bombTime " + bombTime + " " + plantTime);
            if(bombTime > 40 && plantTime != 0)
            {
                plantTime = 0;
                redScore++;
                boolean bEndGame = false;
                if(redScore >= wins)
                    bEndGame = true;
                endRound((byte)1, bEndGame);
            }
        }
        
        public synchronized void write(byte buffer [], short size, Client c)
        {
            c.write(buffer, size);
        }
        
        public void write(EnterAck enterAck, Client client)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    System.out.println("Sending EnterAck to " + c.getLoginName());
                    write(enterAck.getData(c.getSeqNum(), client, EnterAck.ROOM_OK), enterAck.getSize(), c);
                }
            }
        }
        
        public void write(RoomCharsAck charsAck)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    System.out.println("Sending RoomCharsAck to " + c.getLoginName());
                    write(charsAck.getData(c.getSeqNum(), this), charsAck.getSize(), c);
                }
            }
        }
        
        public void write(AddAISlotAck aiAck)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                    write(aiAck.getData(c.getSeqNum()), aiAck.getSize(), c);
            }
        }

        public void write(ExitAck exitAck)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    write(exitAck.getData(c.getSeqNum()), exitAck.getSize(), c);
                }
            }
        }
        
        public void write(ChangeBossAck changeBossAck)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    write(changeBossAck.getData(c.getSeqNum()), changeBossAck.getSize(), c);
                }
            }
        }
        
        public void write(ServerMsg msg)
        {
            for(int i = 0; i < users.length; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    write(msg.getData(c.getSeqNum()), msg.getSize(), c);
                }
            }
        }
        
        public synchronized void addUser(Client user)
        {
            short slot = -1;
            for(int i = 0; i < users.length; i++)
                if(users[i] == null)
                {
                    users[i] = user;
                    slot = (short)i;
                    break;
                }
            nUsers++;
            user.setRoomNumber(this.roomNumber);
            user.setRoomSlot(slot);
            byte userTeam = 1;
            if(getTeamCount((byte)2) < getTeamCount((byte)1))
                userTeam = 2;
            user.setRoomTeam((byte) userTeam);
            int team_slot = -1;
            for(int j = 0; j < roomCapacity; j++)
            {
                boolean bFound = false;
                for(int i = 0; i < users.length; i++)
                {
                    if(users[i] != null && users[i] != user)
                        if(users[i].getRoomTeam() == user.getRoomTeam())
                            if(users[i].getRoomTeamSlot() == j)
                            {
                                System.out.println("Joining Found Same " + users[i].getLoginName() + " " + users[i].getRoomTeam() + " " + users[i].getRoomTeamSlot());
                                bFound = true;
                            }
                }
                if(bFound == false)
                {
                    user.setRoomTeamSlot((byte) j);
                    user.setRoomSlotStatus((byte)0);
                    break;
                }
            }
        }
        
        /**
         * Seat an AI bot as a real roster member (Sec 278). The bot has no server-side connection until now,
         * so the client had no roster entry to seat and self-placed every bot at one slot -> collision.
         * This puts a socket-less bot Client in users[] at the lowest free ABSOLUTE slot; RoomCharsAck then
         * emits it with [+0] = its array index (distinct) and a distinct sessionId, so the client seats it
         * cleanly. serverTeam: 1=blue, 2=red. Returns the absolute slot, or -1 if the room is full.
         */
        public synchronized Client addBot(byte serverTeam, int nameIdx)
        {
            if (serverTeam != 1 && serverTeam != 2) {
                System.out.println("[AI] addBot REJECTED - invalid team " + serverTeam);
                return null;
            }
            // 8v8 is a 16-seat room.  A human host occupies one of those
            // seats, therefore allow at most fifteen bots globally and never
            // overfill either side beyond eight players.
            if (getTeamCount(serverTeam) >= 8 || nUsers >= 16) {
                System.out.println("[AI] addBot REJECTED - capacity team=" + serverTeam
                        + " teamCount=" + getTeamCount(serverTeam) + " users=" + nUsers);
                return null;
            }
            // SLOTTING (AI-DM, post-WolfHunt-fix): fill the LOWEST free slot (contiguous) up to the room
            // capacity, minus headroom for the client's +1 render offset. This fixes both slot bugs at once:
            // the parity-only fill left gaps (the "skip"), and the +1 pushed slots past the cap (the crash).
            // Teams now fill by AI-DM's own rules instead of a slot-parity trick. Parity path kept as a
            // fallback toggle (BOT_FILL_CONTIGUOUS=false).
            int cap = (roomCapacity > 0 && roomCapacity <= users.length) ? roomCapacity : users.length;
            // With direct slot mapping every declared room seat is usable.
            // Reserve one extra position only if a future compatibility mode
            // explicitly restores the old client-side +1 slot offset.
            int maxServerSlot = Math.min(users.length - 1,
                    cap - (BOT_COMPENSATE_PLUS1 ? 2 : 1));
            if (maxServerSlot < 0) maxServerSlot = users.length - 1;
            int slot = -1;
            if (BOT_FORCE_SLOT >= 0 && BOT_FORCE_SLOT < users.length && users[BOT_FORCE_SLOT] == null) {
                slot = BOT_FORCE_SLOT;
                System.out.println("[AI] BOT_FORCE_SLOT: forcing bot to abs slot " + slot);
            } else if (BOT_FILL_CONTIGUOUS) {
                for (int i = 0; i <= maxServerSlot; i++)
                    if (users[i] == null) { slot = i; break; }
            } else {
                int parity = (serverTeam == 1) ? 0 : 1;   // legacy: team1->even, team2->odd
                if (BOT_COMPENSATE_PLUS1) parity = 1 - parity;
                for (int i = parity; i <= maxServerSlot; i += 2)
                    if (users[i] == null) { slot = i; break; }
            }
            if(slot < 0) {
                System.out.println("[AI] addBot REJECTED - no free slot <= maxServerSlot=" + maxServerSlot
                        + " (roomCapacity=" + roomCapacity + " cap=" + cap + " nUsers=" + nUsers + " users.length="
                        + users.length + "). If only 1 bot ever seats, this cap is the culprit.");
                return null;   // no free slot within capacity (no crash-past-cap)
            }

            int seed = 0;
            for(Client u : users) if(u != null && u.isBot()) seed++;   // distinct sessionId seed per bot
            // Display name shown on the roster/scoreboard in ROSTER_ONLY mode (mode 2), where we no longer
            // send the 0x1675 string-table name ("AI EASY N"). Keep the same look via the loginName.
            Client bot = new Client("AI EASY " + (seed + 1), seed);
            users[slot] = bot;
            nUsers++;
            bot.setRoomNumber(this.roomNumber);
            bot.setRoomSlot((short) slot);
            bot.setRoomTeam(serverTeam);
            // per-team slot = lowest free index within this team (same logic as addUser)
            for(int j = 0; j < roomCapacity; j++)
            {
                boolean bFound = false;
                for(int i = 0; i < users.length; i++)
                    if(users[i] != null && users[i] != bot
                       && users[i].getRoomTeam() == serverTeam
                       && users[i].getRoomTeamSlot() == j)
                        bFound = true;
                if(!bFound) { bot.setRoomTeamSlot((byte) j); break; }
            }
            bot.setRoomSlotStatus((byte) 0);   // ATLAS: was (byte)1; visible real players have slotStatus 0 -> role/+0x1b6/+0x1b7=0. Bot had 1 -> invisible. spawnBots sets 4 at round start (same as humans).
            System.out.println("[AI] seated bot '" + bot.getLoginName() + "' -> abs slot " + slot
                    + " team " + serverTeam + " teamSlot " + bot.getRoomTeamSlot()
                    + " sessionId 0x" + Integer.toHexString(bot.getSessionId() & 0xffff)
                    + " | client should render at slot " + (slot + 1) + " = "
                    + (((slot + 1) % 2 == 0) ? "BLUE(even)" : "RED(odd)")
                    + (BOT_COMPENSATE_PLUS1 ? " [+1 compensation ON]" : " [+1 compensation OFF]"));
            return bot;
        }

        private int lastBotSpawnRound = -1;
        /**
         * Proactively spawn every AI bot in the room (Sec 282). Bots have no connection so they can't send
         * their own STARTROUND_REQ; the server must send each bot's SpawnAck (0x1443) with payload[1] = the
         * bot's roster [+0] slot. Binary confirmed FUN_3423a4c0 spawns straight off the message (slot/char),
         * not gated on UDP - so this alone fixes "invisible in-field" and "wrong slot". Runs once per round.
         */
        public synchronized void spawnBots()
        {
            // See BOT_SPAWN_MODE for what each mode sends. Live findings: 0x1675 handles the in-field body;
            // the scoreboard row comes from the status-3 promotion; two status promotions => two rows.
            if (BOT_SPAWN_MODE == 2) {   // NONE: 0x1675-only, no scoreboard row (in-field body only)
                System.out.println("[AI] BOT_SPAWN_MODE=2 (NONE): skip all player-spawn treatment (0x1675-only)");
                return;
            }
            int rn = getRoundNumber();
            if(rn == lastBotSpawnRound) return;   // already spawned this round (each human's STARTROUND calls us)
            lastBotSpawnRound = rn;
            int _botCount = 0;
            for (Client u : users) if (u != null && u.isBot()) _botCount++;
            System.out.println("[AI] spawnBots: " + _botCount + " bot(s) in the room to spawn this round"
                    + " -> if this is 1 but you added more, the regression is in SEATING (addBot cap/reject);"
                    + " if it's >1 but only one renders, it's in the client body build.");
            for(Client u : users)
            {
                if(u == null || !u.isBot()) continue;
                short teamBasic = (u.getRoomTeam() == 1) ? (short) 30005 : (short) 30006;   // 1=blue,2=red
                // Sec 289: a real player, at STARTROUND, gets ChangeSlotStatus(slot,4) broadcast + status=4
                // ("loaded / in-game") BEFORE its SpawnAck (GameServer STARTROUND handler). Bots never send
                // STARTROUND so they never reached status 4 -> the client won't render a body for a slot not
                // marked in-game. Mirror it: status->4, then EnterGame (entity), then Spawn (body).
                // FULL only: the status-4 "loaded" precursor. LIVE_ONLY skips it and sends just the single
                // status-3 below, to test whether ONE status promotion = ONE scoreboard row.
                if (BOT_SPAWN_MODE == 0) {
                    ChangeSlotStatus botReady = new ChangeSlotStatus((byte) u.getRoomSlot(), (byte) 4);
                    write(botReady);
                    u.setRoomSlotStatus((byte) 4);
                }
                // DOUBLE-ROW TEST: the bot is seated by BOTH 0x1675 (AI slot) AND this EnterGameAck (field
                // player) at the SAME absolute slot -> the client draws TWO scoreboard rows (live-confirmed:
                // both rows at slot 3, they moved together when the slot changed, so it's not slot-number tied).
                // Sec 292: the BODY is painted by status=3 (the ChangeSlotStatus below), NOT by EnterGame - so
                // this 0x161d is likely just a redundant roster entry. Dropped to test: body should still render
                // via SpawnAck + status3, and the phantom 2nd row should disappear. If the body vanishes, the
                // EnterGame IS load-bearing and we reconcile instead of dropping.
                // EnterGameAck botEnter = new EnterGameAck((byte) u.getRoomSlot(), u.getRoomTeam(), u.getRoomTeamSlot());
                // write(botEnter);
                // SpawnAck (0x1443): confirmed a no-op for bots (on/off = no change). Only sent in FULL.
                // Char delivery. teamBasic = 30005 (blue) / 30006 (red) by roomTeam. BOT_FORCE_CHAR (>=0)
                // overrides it to a fixed id and forces the SpawnAck to send even in LIVE_ONLY, so we can test
                // whether the bot's TEAM/COLOR (and form) come from its CHARACTER (faction/form-locked) rather
                // than a team byte the AI path can't carry. Force 30005 -> should turn the bot BLUE if so.
                short charToSpawn = (BOT_FORCE_CHAR >= 0) ? (short) BOT_FORCE_CHAR : teamBasic;
                if (BOT_SPAWN_MODE == 0 || BOT_FORCE_CHAR >= 0) {
                    SpawnAck botSpawn = new SpawnAck((byte) getRoomMaster(), (byte) u.getRoomSlot(), charToSpawn);
                    write(botSpawn);
                    System.out.println("[AI] SpawnAck char=" + charToSpawn + " for '" + u.getLoginName() + "'"
                            + (BOT_FORCE_CHAR >= 0 ? " (FORCED via BOT_FORCE_CHAR)" : " (teamBasic by roomTeam)"));
                }
                // Sec 292 BREAKTHROUGH (Binary +0x50 watchpoint): the "player is LIVE" promotion is a plain
                // TCP message - CS_FD_CHANGESLOTSTATUS_ACK 0x1603 with status=3. The server sends it for admin
                // at match-start (status 1->3, writer FUN_34187940) and that's what makes a body render. Bots
                // only got status 4 -> never promoted to live -> body built but never painted. Send 0x1603
                // (slot, 3) for the bot after its spawn, exactly as a real player gets it.
                // Team/color: EnterGameAck (0x161d) carries roomTeam into the red/blue color setter. Sent
                // before the LIVE promotion so the side is set before the body paints. This is the message
                // whose removal made bots default to red / odd slot.
                if (BOT_SEND_ENTERGAME) {
                    EnterGameAck botEnter = new EnterGameAck((byte) u.getRoomSlot(), u.getRoomTeam(), u.getRoomTeamSlot());
                    write(botEnter);
                    System.out.println("[AI] EnterGameAck (team/color) bot '" + u.getLoginName() + "' slot="
                            + u.getRoomSlot() + " team=" + u.getRoomTeam() + " teamSlot=" + u.getRoomTeamSlot());
                }
                // status-3 LIVE promotion: sent in BOTH FULL and LIVE_ONLY. Paints the body and (per the live
                // findings) adds the scoreboard row. LIVE_ONLY sends ONLY this -> testing one status = one row.
                ChangeSlotStatus botLive = new ChangeSlotStatus((byte) u.getRoomSlot(), (byte) 3);
                write(botLive);
                u.setRoomSlotStatus((byte) 3);
                System.out.println("[AI] spawn mode=" + BOT_SPAWN_MODE + " -> LIVE bot '" + u.getLoginName()
                        + "' slot=" + u.getRoomSlot() + " team=" + u.getRoomTeam()
                        + (BOT_SPAWN_MODE == 1 ? " (LIVE_ONLY: single status-3, testing ONE row)"
                                              : " (FULL: status4 + SpawnAck + status3)"));
            }
        }

        public synchronized void changeTeam(Client user)
        {
            byte newTeam = 1;
            if (user.getRoomTeam() == 1) {
                newTeam = 2;
            }
            user.setRoomTeam(newTeam);
            for(int j = 0; j < roomCapacity; j++)
            {
                boolean bFound = false;
                for(int i = 0; i < users.length; i++)
                {
                    if(users[i] != null && users[i] != user)
                        if(users[i].getRoomTeam() == user.getRoomTeam())
                            if(users[i].getRoomTeamSlot() == j)
                            {
                                System.out.println("Found Same " + users[i].getLoginName() + " " + users[i].getRoomTeam() + " " + users[i].getRoomTeamSlot());
                                bFound = true;
                            }
                }
                if(bFound == false)
                {
                    user.setRoomTeamSlot((byte) j);
                    break;
                }
            }
        }
        
        public synchronized void removeUser(Client user)
        {
            byte team = user.getRoomTeam();
            deadList.remove(user);
            for(int i = 0; i < users.length; i++)
                if(users[i] == user)
                {
                    users[i] = null;
                    if(i == roomMaster)
                        roomMaster = makeNewMaster();
                    break;
                }  
            nUsers--;
            user.setRoomNumber((short)-1);
            user.setRoomSlot((short)-1);
            user.setRoomTeam((byte)-1);
            user.setRoomTeamSlot((byte)-1);
            user.setRoomSlotStatus((byte)0);
            // Teardown (Sec 280): AI bots are real users[] members, so nUsers never reaches 0 on its own -
            // a room emptied of HUMANS would otherwise stay open with only bots (and a bot as master). Count
            // humans; if none remain, drop the bots and close the room.
            int humans = 0;
            for(Client u : users) if(u != null && !u.isBot()) humans++;
            if(humans == 0)
            {
                for(int i = 0; i < users.length; i++) users[i] = null;   // drop the bot stand-ins
                nUsers = 0;
                rooms.remove(this);
                System.out.println("[AI] room " + roomNumber + " has no humans left -> closed (bots dropped)");
                return;
            }
            if(nUsers == 0)
                rooms.remove(this);
            if(getTeamCount(team) == 0)
            {
                if(team == 2){
                    redScore++;
                    endRound((byte)1, true);
                }else if(team == 1)
                {
                    blueScore++;
                    endRound((byte)2, true);
                }
                
            }
        }
        
        public byte makeNewMaster()
        {
            byte newMaster = -1;
            for(int i = 0; i < users.length; i++)
            {
                if(users[i] != null && !users[i].isBot())   // never promote an AI bot to room master
                {
                    newMaster = (byte)i;
                    break;
                }
            }
            if(newMaster < 0)                 // only bots left; room is about to be torn down (removeUser)
                return roomMaster;
            ChangeBossAck changeBossAck = new ChangeBossAck(newMaster);
            write(changeBossAck);
            return newMaster;
        }
        
        public void startGame()
        {
            if(gameStarted == false)
            {
                gameStarted = true;
                redScore = 0;
                blueScore = 0;
                roundNumber = 1;
                for(int i = 0; i < users.length; i++)
                    if(users[i] != null)
                        if(users[i].getRoomSlotStatus() == 1)
                        {
                            Client client = users[i];
                            client.setRoomSlotStatus((byte)3);
                            ChangeSlotStatus changeSlotStatus = new ChangeSlotStatus(this.getUserSlot(client), (byte)3);
                            write(changeSlotStatus);
                        }
                
            }

        }
        
        public void startRound()
        {
            roundTime = System.currentTimeMillis();
            roundStarted = true;
            for(int i = 0; i < 5; i++)
                flags[i] = 0;
        }
        
        public void endGame()
        {
            gameStarted = false;
        }
        
        public void endRound(byte winTeam, boolean bEndGame)
        {
            if(!roundStarted)
                return;
            roundNumber++;
            Room room = this;
            EndRoundAck endRoundAck = new EndRoundAck(roundNumber, winTeam, (byte) redScore, (byte) blueScore);
            room.write(endRoundAck);
            EndGameAck endGameAck = new EndGameAck();
            RestartRoundAck restartRoundAck = new RestartRoundAck();
            boolean isEndGame = bEndGame;
            deadList.clear();
            if (isEndGame) {
                endGame();
            }
            roundStarted = false;
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    if (!isEndGame) {
                        roundStarted = true;
                        room.write(restartRoundAck);
                    } else {
                        room.write(endGameAck);
                        for (int i = 0; i < users.length; i++) {
                            if (users[i] != null) {
                                users[i].setRoomSlotStatus((byte)0);
                            }
                        }
                    }
                }
            }, 10000);
        }
        
        public void playerDead(Client c, PlayerDeadAck playerDeadAck)
        {
            Room room = this;
            boolean bEndGame = false;
            if(c != null)
            {
                if(c.roomTeam == 2)
                {
                    if (isDeathMatch()) {
                        redScore++;
                        if (redScore >= points) {
                            endRound((byte)1, bEndGame);
                        }
                    }

                    if (isIceHold() || isDestruction()) {
                        deadList.add(c);
                        if(isTeamDead(c.roomTeam))
                        {
                        redScore++;
                        if (redScore >= wins)
                            bEndGame = true;
                        endRound((byte)1, bEndGame);
                        }
                    }

                }
                    
                if (c.roomTeam == 1) {
                    if (isDeathMatch()) {
                        blueScore++;
                        if (blueScore >= points) {
                            endRound((byte)2, bEndGame);
                        }
                    }

                    if (isIceHold() || isDestruction()) {
                        deadList.add(c);
                        if(isTeamDead(c.roomTeam))
                        {
                        blueScore++;
                        if (blueScore >= wins)
                            bEndGame = true;
                        endRound((byte)2, bEndGame);
                        }
                    }
                }
            }
            if (c == null) {
                System.out.println("[DEATH] ignored: victim slot did not resolve");
                return;
            }
            playerDeadAck.setScore(room.getRedScore(), room.getBlueScore());
            playerDeadAck.setSlot((byte)c.getRoomSlot());
            room.write(playerDeadAck);
            int killerSlot = playerDeadAck.getKillerSlot() & 0xff;
            if (killerSlot >= users.length || users[killerSlot] == null) {
                System.out.println("[DEATH] ignored DB stats: invalid killer slot=" + killerSlot);
                return;
            }
            int killer_id = users[killerSlot].getUserId();
            int dead_id = c.getUserId();
            // Synthetic AI clients have user_id=0. Never feed those IDs to
            // foreign-keyed kill_log/users updates.
            if (killer_id > 0 && dead_id > 0) {
                Users userDb = new Users();
                userDb.registerKill(killer_id, dead_id);
            } else {
                System.out.println("[DEATH] AI event: killerId=" + killer_id + " victimId=" + dead_id
                        + " (score broadcast only, no account DB update)");
            }
        }
        
        public void plantBomb()
        {
            plantTime = System.currentTimeMillis();
            roundTime = roundTime + 60000;
        }
        
        public void removeBomb()
        {
            plantTime = 0;
            blueScore++;
            boolean bEndGame = false;
            if (blueScore >= wins) {
                bEndGame = true;
            }
            endRound((byte)2, bEndGame);
        }
        
        public void changeFlag(byte flag, byte team)
        {
            if(flag >= 0 && flag < 5)
            {
                flags[flag] = team;
            }
            boolean same = true;
            for(int i = 0; i < 5; i++)
            {
                if(flags[i] != flags[0])
                {
                    same = false;
                    break;
                }
            }
            if(same)
            {
                boolean bEndGame = false;
                if(team == 1)
                    redScore++;
                else if(team == 2)
                    blueScore++;
                if (redScore >= wins || blueScore >= wins)
                    bEndGame = true;
                endRound(team, bEndGame);
                
                for(int i = 0; i < 5; i++)
                    flags[i] = 0;
            }
        }
        
        public int getFlagCount(byte team)
        {
            int count = 0;
            for(int i = 0; i < 5; i++)
                if(flags[i] == team)
                    count++;
            return count;
        }
        
        public ArrayList<Client> getUsers()
        {
            ArrayList<Client> clients = new ArrayList<Client>();
            for(int i = 0; i < users.length; i++)
                if(users[i] != null)
                {
                    clients.add(users[i]);
                }
            return clients;
        }
        
        public byte getUserSlot(Client c)
        {
            for(int i = 0; i < users.length; i++)
            {
                if(users[i] == c)
                    return (byte)i;
            }
            return -1;
        }
        
        public short getRoomNumber()
        {
            return roomNumber;
        }

        public String getRoomTitle()
        {
            return title;
        }
 
        public String getRoomPassword()
        {
            return password;
        }
        
        public byte getNumberOfUsers()
        {
            return nUsers;
        }
        
        public byte getMap() {
            return map;
        }

        public int getMode() {
            return mode;
        }

        public byte getWins() {
            return wins;
        }

        public byte getPoints() {
            return points;
        }

        public byte getCapacity() {
            return roomCapacity;
        }

        public short getTime() {
            return time;
        }
      
        public short getRedScore() {
            return redScore;
        }
        
        public short getBlueScore() {
            return blueScore;
        }
        
        public short getCurrentTime()
        {
            long currentTime = (short) ((System.currentTimeMillis() - roundTime) / 1000);
            System.out.println("Current Time " + currentTime + " " + (short) (time - currentTime));
            return (short) (time - currentTime);
        }
        
        public byte getRoundNumber()
        {
            return roundNumber;
        }
        
        public boolean isDeathMatch()
        {
            int m = mode & 0xFF;
            if(m == 2 || m == 18 || m == 10)
                return true;
            return false;
        }
        public boolean isAiDeathMatch()
        {
            // AI Deathmatch = base DM (0x02) tagged with the AI flag in the upper bytes (0x040000).
            return (mode & 0x040000) != 0;
        }
        public boolean isConquest() {
            int m = mode & 0xFF;
            if (m == 1 || m == 17 || m == 9 || m == 129) {
                return true;
            }
            return false;
        }
        public boolean isIceHold()
        {
            int m = mode & 0xFF;
            if(m == 32 || m == 48)
                return true;
            return false;
        }
        public boolean isDestruction() {
            int m = mode & 0xFF;
            if (m == 4 || m == 20 || m == 132) {
                return true;
            }
            return false;
        }
        public boolean isExconquest() {
            int m = mode & 0xFF;
            if (m == 65) {
                return true;
            }
            return false;
        }
        public boolean isWolfhunt() {
            int m = mode & 0xFF;
            if (m == 128) {
                return true;
            }
            return false;
        }
        public boolean isFull()
        {
            return roomCapacity == nUsers;
        }
        public int getTeamCount(byte team)
        {
            int count = 0;
            for(int i = 0; i < 17; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    if(c.getRoomTeam() == team)
                        count++;
                }
            }
            return count;
        }
        public int getTeamReadyCount(byte team)
        {
            int count = 0;
            for(int i = 0; i < 17; i++)
            {
                Client c = users[i];
                if(c != null)
                {
                    if(c.getRoomTeam() == team && (c.getRoomSlotStatus() > 0 || c.isBot()))
                        count++;   // bots are AI: always "ready" so an AI-filled team satisfies the start gate
                }
            }
            return count;
        }
        public boolean isTeamDead(byte team)
        {
            if(isDestruction() && (plantTime != 0 && team == 2) )
                return false;
            int count = 0;
            for(int i = 0; i < deadList.size(); i++)
            {
                Client c = deadList.get(i);
                if(c != null)
                {
                    if(c.getRoomTeam() == team)
                        count++;
                }
            }
            return (count == getTeamCount(team));
        }
        public int getTeamDeadCount(byte team)
        {
            int count = 0;
            for(int i = 0; i < deadList.size(); i++)
            {
                Client c = deadList.get(i);
                if(c != null)
                {
                    if(c.getRoomTeam() == team)
                        count++;
                }
            }
            return count;
        }
        public int getRoomMaster()
        {
            return roomMaster;
        }
        public void userRevived(Client c) {
            deadList.remove(c);
        }
    }
    @Override
    public synchronized void removeClient(Client c)
    {
        int n = c.getRoomNumber();
        if(n >= 0)
        {
            Room room = getRoomWithNumber((short) n);
            if(room != null)
            {
                ExitAck exitAck = new ExitAck(room.getUserSlot(c));
                room.write(exitAck);
                room.removeUser(c);
            }
        }
        super.removeClient(c);
    }
}

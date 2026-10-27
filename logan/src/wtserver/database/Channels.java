/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.database;

import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 * DB-driven channel list. Reads channels.php (?req=get), which now returns 7 fields per
 * channel: name;port;is_open;f1;f2;f3;f4;  (repeating). Both lobby acks (WorldListAck +
 * WorldInfoAck) build from this ONE list, so their counts can never drift.
 *
 * @author MSI
 */
public class Channels extends DatabaseConnection {

    private String weburl;

    public Channels()
    {
        weburl = webserver + "channels.php";
    }

    public class Channel {
        private String channelName;
        private short  channelPort;
        private int    isOpen;   // 1 = open (WorldList id 0x03), 0 = closed (0x00)
        private int    f1;       // WorldInfo stat fields (mirrored from real captures)
        private int    f2;       // ~ capacity (captured 10000)
        private int    f3;
        private int    f4;       // channel type/level marker (New Players = 20)

        public Channel(String channelName, short channelPort,
                       int isOpen, int f1, int f2, int f3, int f4)
        {
            this.channelName = channelName;
            this.channelPort = channelPort;
            this.isOpen = isOpen;
            this.f1 = f1;
            this.f2 = f2;
            this.f3 = f3;
            this.f4 = f4;
        }

        public String getChannelName() { return channelName; }
        public short  getChannelPort() { return channelPort; }
        public int    getIsOpen()      { return isOpen; }
        public boolean isOpen()        { return isOpen != 0; }
        public int    getF1()          { return f1; }
        public int    getF2()          { return f2; }
        public int    getF3()          { return f3; }
        public int    getF4()          { return f4; }
    }

    public ArrayList<Channel> getChannels()
    {
        ArrayList<Channel> channels = new ArrayList<Channel>();

        String web = weburl + "?req=get";

        String response = getResponse(web);
        if (response == null) response = "";

        StringTokenizer st = new StringTokenizer(response, ";");

        // 7 fields per channel: name;port;is_open;f1;f2;f3;f4
        // Only build a Channel when a full set of 7 tokens is available, so a stray
        // trailing token can never create a half-built (and mis-counted) channel.
        while (st.countTokens() >= 7)
        {
            String name    = st.nextToken();
            int    port    = Integer.parseInt(st.nextToken().trim());
            int    isOpen  = Integer.parseInt(st.nextToken().trim());
            int    f1      = Integer.parseInt(st.nextToken().trim());
            int    f2      = Integer.parseInt(st.nextToken().trim());
            int    f3      = Integer.parseInt(st.nextToken().trim());
            int    f4      = Integer.parseInt(st.nextToken().trim());

            channels.add(new Channel(name, (short) port, isOpen, f1, f2, f3, f4));
        }

        return channels;
    }
}

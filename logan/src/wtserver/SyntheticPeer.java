package wtserver;

import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;

/**
 * SyntheticPeer — LTNet-aware stand-in "second client" for WolfTeam p2p.
 * LIVE-CONFIRMED: client sends LTNet CONNECT  CC FF 00 00 02  (TOKEN 0x0000FFCC, sub=2);
 * establish by replying CONNECT_RESPONSE(accept)  CC FF 00 00 0B. Crypto flags VALIDATED
 * (leWords=true, beBlock=false) against the known pair.
 */
public class SyntheticPeer {
    final int port; final String mode; final byte[] replayBytes;
    final boolean symmetric, keepalive;
    final DatagramSocket sock; final PrintWriter log;
    LTMatchCrypto crypto;
    volatile InetSocketAddress clientAddr = null;
    long rx=0, tx=0; volatile boolean weSentConnect=false;

    SyntheticPeer(int port,String mode,byte[] replay,boolean symmetric,boolean keepalive) throws IOException {
        this.port=port; this.mode=mode; this.replayBytes=replay; this.symmetric=symmetric; this.keepalive=keepalive;
        this.sock=new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));
        this.sock.setReuseAddress(true);
        this.log=new PrintWriter(new FileWriter("synthetic_peer_capture.log",true),true);
        try { byte[] s=Files.readAllBytes(Paths.get("bf_schedule.bin"));
              this.crypto=new LTMatchCrypto(s,true,false);
              logLine("crypto loaded (leWords=true beBlock=false)"); }
        catch(Exception e){ this.crypto=null; logLine("NOTE: bf_schedule.bin not loaded ("+e.getMessage()+")"); }
    }
    static String hex(byte[] b,int len){StringBuilder s=new StringBuilder();for(int i=0;i<len;i++)s.append(String.format("%02x",b[i]&0xff));return s.toString();}
    static byte[] fromHex(String s){s=s.replaceAll("[^0-9a-fA-F]","");byte[]o=new byte[s.length()/2];for(int i=0;i<o.length;i++)o[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return o;}
    void logLine(String s){String l=System.currentTimeMillis()+" "+s;System.out.println(l);log.println(l);}
    void send(byte[] pkt,InetSocketAddress to,String tag) throws IOException {
        sock.send(new DatagramPacket(pkt,pkt.length,to));
        logLine("TX#"+(++tx)+" "+tag+" to "+to.getAddress().getHostAddress()+":"+to.getPort()+" len="+pkt.length+" : "+hex(pkt,pkt.length));
    }
    boolean handleHandshake(byte[] pkt,InetSocketAddress from) throws IOException {
        if (LTNet.isUnconnected(pkt)) {
            LTNet.Reader r=new LTNet.Reader(pkt); r.readUInt32(); int sub=(int)r.readBits(LTNet.MSG_BITS);
            switch(sub){
                case LTNet.MSG_CONNECT:
                    send(LTNet.connectResponse(true,false),from,"CONNECT_RESPONSE(accept)");
                    if (symmetric && !weSentConnect){ LTNet.Writer w=new LTNet.Writer();
                        w.writeUInt32(LTNet.TOKEN); w.writeBits(LTNet.MSG_CONNECT,LTNet.MSG_BITS); w.writeBool(true);
                        send(w.toBytes(),from,"our CONNECT"); weSentConnect=true; }
                    return true;
                case LTNet.MSG_QUERY: send(LTNet.queryResponse("wtpeer",0,false,0),from,"QUERY_RESPONSE"); return true;
                case LTNet.MSG_CONNECT_RESPONSE: logLine("  (peer ACK'd our CONNECT - bidirectional up)"); return true;
                case LTNet.MSG_PING: long pv=r.readBits(LTNet.PING_BITS); send(LTNet.pingResponse(pv),from,"PING_RESPONSE"); return true;
                default: logLine("  unconnected sub="+sub+" (no auto-reply)"); return false;
            }
        }
        if (pkt.length>=16 && crypto!=null) {
            byte[] hdr=crypto.decryptHeader(pkt); boolean fpOk=LTMatchCrypto.fingerprintOk(hdr); int type=LTMatchCrypto.type(hdr);
            logLine("  connected hdr="+hex(hdr,8)+" fpOk="+fpOk+" type=0x"+Integer.toHexString(type)+" session="+LTMatchCrypto.session(hdr)+" seq="+LTMatchCrypto.seq(hdr));
            if (type==LTMatchCrypto.TYPE_CONNECT){ send(crypto.buildAcceptFromConnect(pkt),from,"match ACCEPT"); return true; }
        }
        return false;
    }
    void keepaliveLoop(){
        while(true){ try{ Thread.sleep(200); InetSocketAddress d=clientAddr;
            if (d!=null && keepalive && symmetric && !weSentConnect){ LTNet.Writer w=new LTNet.Writer();
                w.writeUInt32(LTNet.TOKEN); w.writeBits(LTNet.MSG_CONNECT,LTNet.MSG_BITS); w.writeBool(true);
                send(w.toBytes(),d,"our CONNECT(retry)"); } }catch(Exception e){} }
    }
    void run() throws IOException {
        logLine("SyntheticPeer UP 127.0.0.1:"+port+" mode="+mode+" symmetric="+symmetric);
        if (keepalive){ Thread t=new Thread(this::keepaliveLoop,"ka"); t.setDaemon(true); t.start(); }
        byte[] buf=new byte[4096];
        while(true){ DatagramPacket p=new DatagramPacket(buf,buf.length); sock.receive(p);
            int n=p.getLength(); InetSocketAddress from=(InetSocketAddress)p.getSocketAddress(); clientAddr=from;
            byte[] pkt=Arrays.copyOf(p.getData(),n);
            logLine("RX#"+(++rx)+" from "+from.getAddress().getHostAddress()+":"+from.getPort()+" len="+n+" : "+hex(pkt,n));
            if (mode.equals("handshake")) handleHandshake(pkt,from);
            else if (mode.equals("echo")) send(pkt,from,"echo");
            else if (mode.equals("replay") && replayBytes!=null) send(replayBytes,from,"replay");
            // capture: no reply
        }
    }
    public static void main(String[] a) throws Exception {
        if (a.length<1){System.err.println("usage: SyntheticPeer <port> [handshake|capture|echo|replay] [hexfile] [--symmetric] [--nokeepalive]");return;}
        int port=Integer.parseInt(a[0]); String mode=a.length>=2?a[1]:"handshake";
        byte[] replay=null; boolean sym=false, ka=true;
        for(int i=2;i<a.length;i++){ if(a[i].equals("--symmetric"))sym=true; else if(a[i].equals("--nokeepalive"))ka=false; else replay=fromHex(new String(Files.readAllBytes(new File(a[i]).toPath()))); }
        if (mode.equals("capture")) ka=false;
        new SyntheticPeer(port,mode,replay,sym,ka).run();
    }
}

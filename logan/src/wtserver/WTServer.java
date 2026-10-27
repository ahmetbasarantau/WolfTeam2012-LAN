/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver;

import java.io.IOException;


/**
 *
 * @author MSI
 */
public class WTServer {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        System.out.println("WT Server v0.1");
        // The 2012 client starts directly with its remembered LAN name.
        BrokerServer legacyBroker = new BrokerServer(30706);
        BrokerServer  broker = new BrokerServer(40706);
        GameServer server2 = new GameServer(40707);
        //GameServer server3 = new GameServer(40709);
        System.out.println("WolfTeam LAN sunucusu hazır.");
        try {
            new java.util.concurrent.CountDownLatch(1).await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        System.exit(0);
    }
    
}

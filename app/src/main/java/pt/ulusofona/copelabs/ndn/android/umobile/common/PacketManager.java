/**
 * @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-12-22
 * This interface refer all methods that packet manage and
 * the requester must implement.
 * @author Miguel Tavares (COPELABS/ULHT)
 */

package pt.ulusofona.copelabs.ndn.android.umobile.common;


import android.content.Context;

import pt.ulusofona.copelabs.ndn.android.umobile.connectionoriented.Packet;

public interface PacketManager {

    interface Manager {
        void enable(Context context, PacketManager.Requester requester, OpportunisticFaceManager oppFaceManager);
        void disable();
        void onTransferInterest(String sender, String recipient, byte[] payload, int nonce);
        void onTransferData(String sender, String recipient, byte[] payload, String name);
        void onPacketTransferred(String pktId);
        void onCancelInterest(long faceId, int nonce);
    }

    interface Requester {
        void onInterestPacketTransferred(String recipient, int nonce);
        void onDataPacketTransferred(String recipient, String name);
        void onCancelPacketSentOverConnectionLess(Packet packet);
        void onSendOverConnectionOriented(Packet packet);
        void onSendOverConnectionLess(Packet packet);
    }

    interface Notifier {
        void onInterestTransferred(String name);
        void onDataReceived(String name);
    }
    
}

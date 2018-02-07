/**
 * @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-12-22
 * This class manages the packet flow over the application.
 * It decides if the packet is transferred over connection less or
 * connection oriented.
 * @author Miguel Tavares (COPELABS/ULHT)
 */

package pt.ulusofona.copelabs.ndn.android.umobile.common;


import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import pt.ulusofona.copelabs.ndn.android.preferences.Configuration;
import pt.ulusofona.copelabs.ndn.android.umobile.connectionoriented.Packet;
import pt.ulusofona.copelabs.ndn.android.wifi.p2p.WifiP2pListener;
import pt.ulusofona.copelabs.ndn.android.wifi.p2p.WifiP2pListenerManager;

public class PacketManagerImpl implements PacketManager.Manager, WifiP2pListener.WifiP2pConnectionStatus {

    /** This variable is used as a label for packets */
    public static final String PACKET_KEY_PREFIX = "PKT:";

    /** This variable is used to debug PacketManagerImpl class */
    private static final String TAG = PacketManagerImpl.class.getSimpleName();

    /** This variable stores the max number of bytes allowed to send over connection less */
    private static final int MAX_PAYLOAD_SIZE_CL = 80;

    /** Maps Packet ID -> Name (WifiP2pCache) */
    private Map<String, Packet> mPendingPackets = new HashMap<>();

    /** Maps Nonce -> Packet ID */
    private Map<Integer, String> mPendingInterestIdsFromNonces = new HashMap<>();

    /** Maps Packet ID -> Nonce */
    private Map<String, Integer> mPendingInterestNoncesFromIds = new HashMap<>();

    /** Maps Name -> Packet ID (WifiP2pCache) */
    private Map<String, String> mPendingDataIdsFromNames = new HashMap<>();

    /** Maps Packet ID -> Name (WifiP2pCache) */
    private Map<String, String> mPendingDataNamesFromIds = new HashMap<>();

    /** This object is need to check if there is a connection oriented connection for an uuid */
    private OpportunisticFaceManager mOppFaceManager;

    /** This variable holds the Wi-Fi P2P connection state */
    private boolean mConnectionEstablished = false;

    /** This interface is used to communicate with the binder */
    private PacketManager.Requester mRequester;

    /** This variable is used to generate packet ids */
    private int mPacketId = 0;

    /** This object holds the application context */
    private Context mContext;

    /** This variable holds the state of this class, if is running or not */
    private boolean mEnable;

    /**
     * This method starts the packet manager
     * @param context application context
     * @param requester packet requester
     * @param oppFaceManager opp face manager
     */
    @Override
    public synchronized void enable(Context context, PacketManager.Requester requester, OpportunisticFaceManager oppFaceManager) {
        if(!mEnable) {
            mContext = context;
            mRequester = requester;
            mOppFaceManager = oppFaceManager;
            WifiP2pListenerManager.registerListener(this);
            mEnable = true;
        }
    }

    /**
     * This method stops the packet manager
     */
    @Override
    public synchronized void disable() {
        if(mEnable) {
            WifiP2pListenerManager.unregisterListener(this);
            mConnectionEstablished = false;
            mEnable = false;
        }
    }

    /**
     * This method is invoked when an interest packet is going to be transferred
     * @param sender sender uuid
     * @param recipient recipient uuid
     * @param payload payload to send
     * @param nonce interest's nonce
     */
    @Override
    public synchronized void onTransferInterest(String sender, String recipient, byte[] payload, int nonce) {
        Log.i(TAG, "Transferring interest from " + sender + " to " + recipient);
        String pktId = generatePacketId();
        Log.i(TAG, "It's packet id is " + pktId);
        Packet packet = new Packet(pktId, sender, recipient, payload);
        mPendingPackets.put(pktId, packet);
        pushInterestPacket(pktId, nonce);
        sendPacket(packet);
    }

    /**
     * This method is invoked when a data packet is going to be transferred
     * @param sender sender uuid
     * @param recipient recipient uuid
     * @param payload payload to send
     * @param name data's name
     */
    @Override
    public synchronized void onTransferData(String sender, String recipient, byte[] payload, String name) {
        Log.i(TAG, "Transferring interest from " + sender + " to " + recipient);
        String pktId = generatePacketId();
        Log.i(TAG, "It's packet id is " + pktId);
        Packet packet = new Packet(pktId, sender, recipient, payload);
        mPendingPackets.put(pktId, packet);
        pushDataPacket(pktId, name);
        sendPacket(packet);
    }

    /**
     * This method is invoked when the packet was already transferred
     * @param pktId id of transferred packet
     */
    @Override
    public synchronized void onPacketTransferred(String pktId) {
        Packet packet = mPendingPackets.remove(pktId);
        if(isDataPacket(pktId)) {
            Log.i(TAG, "WifiP2pCache packet with id " + pktId + " was transferred");
            mRequester.onDataPacketTransferred(packet.getRecipient(), removeDataPacket(pktId));
        } else {
            Log.i(TAG, "Interest packet with id " + pktId + " was transferred");
            mRequester.onInterestPacketTransferred(packet.getRecipient(), removeInterestPacket(pktId));
        }
    }

    /**
     * This method is used to cancel an interest
     * @param faceId interest's face
     * @param nonce interest's nonce
     */
    @Override
    public synchronized void onCancelInterest(long faceId, int nonce) {
        String pktId = mPendingInterestIdsFromNonces.get(nonce);
        Log.i(TAG, "Cancelling interest packet id " + pktId);
        Packet packet = mPendingPackets.remove(pktId);
        if(pktId != null && packet != null) {
            mRequester.onCancelPacketSentOverConnectionLess(packet);
            removeInterestPacket(pktId);
        }
    }

    /**
     * This method is invoked when a Wi-Fi P2P connection established
     * @param intent intent
     */
    @Override
    public void onConnected(Intent intent) {
        Log.i(TAG, "Wi-Fi or Wi-Fi P2P connection detected");
        mConnectionEstablished = true;
    }

    /**
     * This method is invoked when the Wi-Fi P2P connection goes down
     * @param intent intent
     */
    @Override
    public void onDisconnected(Intent intent) {
        Log.i(TAG, "Wi-Fi or Wi-Fi P2P connection dropped");
        mConnectionEstablished = false;
    }

    /**
     * This method is used to generate a packet id
     * @return packet id
     */
    private synchronized String generatePacketId() {
        return PACKET_KEY_PREFIX + (mPacketId++);
    }

    /**
     * This method is used to send the packets and decides where the packet will be sent
     * @param packet packet to send
     */
    private void sendPacket(Packet packet) {
        if(Configuration.isBackupOptionEnabled(mContext)) {
            backupOption(packet);
        } else {
            packetSizeOption(packet);
        }
    }

    /**
     * This method uses the size of packet approach.
     * if the packet has more than MAX_PAYLOAD_SIZE_CL (80) bytes, it will be
     * sent over connection oriented. Otherwise, it will be sent over connection less
     * @param packet packet to be sent
     */
    private void packetSizeOption(Packet packet) {
        if(packet.getPayloadSize() > MAX_PAYLOAD_SIZE_CL) {
            if(mOppFaceManager.isSocketAvailable(packet.getRecipient())) {
                mRequester.onSendOverConnectionOriented(packet);
            }
        } else {
            mRequester.onSendOverConnectionLess(packet);
        }
    }

    /**
     * This method uses the backup approach
     * If there is a connection oriented way to send the data, NDN-OPP will use it.
     * Otherwise, the packet will be send using connection less
     * @param packet packet to be sent
     */
    private void backupOption(Packet packet) {
        if(mConnectionEstablished && mOppFaceManager.isSocketAvailable(packet.getRecipient())) {
            mRequester.onSendOverConnectionOriented(packet);
        } else {
            mRequester.onSendOverConnectionLess(packet);
        }
    }

    /**
     * This method returns true if the packet id passed as a parameter
     * belongs to a data packet. Returns false if not.
     * @param pktId packet id
     * @return
     */
    private boolean isDataPacket(String pktId) {
        return mPendingDataNamesFromIds.get(pktId) != null;
    }

    /**
     * This method associates the name to it's packet id and vice versa
     * @param pktId packet id
     * @param name data's name
     */
    private synchronized void pushDataPacket(String pktId, String name) {
        Log.i(TAG, "Pushing data packet id " + pktId);
        mPendingDataIdsFromNames.put(name, pktId);
        mPendingDataNamesFromIds.put(pktId, name);
    }

    /**
     * This method disassociates the name to it's packet id and vice versa.
     * @param pktId packet id
     * @return returns the name that was associated to this packet id
     */
    private synchronized String removeDataPacket(String pktId) {
        Log.i(TAG, "Removing data packet id " + pktId);
        String name = mPendingDataNamesFromIds.remove(pktId);
        mPendingDataIdsFromNames.remove(name);
        return name;
    }

    /**
     * This method associates the nonce to it's packet id and vice versa
     * @param pktId packet id
     * @param nonce interest's nonce
     */
    private synchronized void pushInterestPacket(String pktId, int nonce) {
        Log.i(TAG, "Pushing interest packet id " + pktId);
        mPendingInterestIdsFromNonces.put(nonce, pktId);
        mPendingInterestNoncesFromIds.put(pktId, nonce);
    }

    /**
     * This method disassociates the name to it's packet id and vice versa.
     * @param pktId packet id
     * @return returns the nonce that was associated to this packet id
     */
    private synchronized int removeInterestPacket(String pktId) {
        Log.i(TAG, "Removing interest packet id " + pktId);
        int nonce = mPendingInterestNoncesFromIds.remove(pktId);
        mPendingInterestIdsFromNonces.remove(nonce);
        return nonce;
    }

}
/**
 *  @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-02-14
 * This class manages the Fragment which displays the PeerTracking and controls Group Formation.
 * @author Seweryn Dynerowicz (COPELABS/ULHT)
 */
package pt.ulusofona.copelabs.ndn.android.ui.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnPushedDataCallback;
import net.named_data.jndn.OnRegisterSuccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import pt.ulusofona.copelabs.ndn.R;
import pt.ulusofona.copelabs.ndn.android.models.NsdService;
import pt.ulusofona.copelabs.ndn.android.ui.adapter.NsdServiceAdapter;
import pt.ulusofona.copelabs.ndn.android.umobile.connectionless.OperationResult;
import pt.ulusofona.copelabs.ndn.android.ui.adapter.PendingInterestAdapter;
import pt.ulusofona.copelabs.ndn.android.ui.dialog.DisplayDataDialog;
import pt.ulusofona.copelabs.ndn.android.ui.dialog.RespondToInterestDialog;
import pt.ulusofona.copelabs.ndn.android.ui.tasks.JndnProcessEventTask;
import pt.ulusofona.copelabs.ndn.android.ui.tasks.RegisterPrefixForPushedDataTask;
import pt.ulusofona.copelabs.ndn.android.ui.tasks.RegisterPrefixTask;
import pt.ulusofona.copelabs.ndn.android.umobile.common.OpportunisticDaemon;
import pt.ulusofona.copelabs.ndn.android.umobile.connectionoriented.OpportunisticConnectivityManager;
import pt.ulusofona.copelabs.ndn.android.umobile.connectionoriented.OpportunisticPeer;
import pt.ulusofona.copelabs.ndn.android.umobile.common.OpportunisticPeerTracker;
import pt.ulusofona.copelabs.ndn.android.umobile.nsd.NsdServiceDiscoverer;
import pt.ulusofona.copelabs.ndn.databinding.FragmentOppPeerTrackingBinding;
import pt.ulusofona.copelabs.ndn.databinding.ItemNdnOppPeerBinding;

/** Interface to the Peer Tracking functionality of NDN-Opp. This Fragment is responsible for integrating
 * the functionalities of the NsdServiceTracker, the WifiP2pPeerTracker and the WifiP2pConnectivityManager.
 *
 * The interactions between these three components is as follows;
 *
 * - The Peer Tracker provides the up-to-date list of Wi-Fi P2P devices running NDN-Opp that were encountered
 * - The Connectivity PacketManager is used to take care of the formation of a Wi-Fi Direct Group (whether to form a new one or join an existing one)
 * - The NSD Service Tracker is used to know which NDN-Opp daemon can be reached within the Group to which the current device is connected (if it is)
 */
public class OpportunisticPeerTracking extends Fragment implements Observer, View.OnClickListener, AdapterView.OnItemClickListener, OnInterestCallback, OnData, OnRegisterSuccess, OnPushedDataCallback {
    private static final String TAG = OpportunisticPeerTracking.class.getSimpleName();

    public static final String PREFIX = "/ndn/multicast/opp";
    public static final String EMERGENCY = PREFIX + "/emergency";

    private static int PROCESS_INTERVAL = 1000;
    public static double INTEREST_LIFETIME = 600000;

    private Context mContext;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mWifiP2pChannel;

    private FragmentOppPeerTrackingBinding mBinding;

    private OpportunisticConnectivityManager mWifiP2pConnectivityManager = new OpportunisticConnectivityManager();
    private OpportunisticPeerTracker mPeerTracker = new OpportunisticPeerTracker();
    private NsdServiceDiscoverer mNsdServiceDiscoverer;

    private Map<String, OpportunisticPeer> mPeers = new HashMap<>();
    private NsdServiceDiscoverer mServiceTracker = NsdServiceDiscoverer.getInstance();
    private Map<String, NsdService> mServices = new HashMap<>();

    // Two variables to remember whether to Group-related buttons have to be enabled or disabled.
    private boolean mCanFormGroup = false; // = can find another potential Group Owner in the vicinity
    private boolean mIsConnectedToGroup = false; // Determines whether the LEAVE button is enabled or not

    private OpportunisticPeerAdapter mPeerAdapter;
    private NsdServiceAdapter mNsdServiceAdapter;
    private PendingInterestAdapter mInterestAdapter;

    private List<Interest> mPendingInterests = new ArrayList<>();

    private Face mFace;

    /** Fragment lifecycle method. See https://developer.android.com/guide/components/fragments.html
     * @param context Android-provided Application context
     */
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        Log.v(TAG, "onAttach");
        mContext = context;
        mWifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(context, Looper.getMainLooper(), null);
        mWifiP2pConnectivityManager.enable(context);

        mPeerAdapter = new OpportunisticPeerAdapter(mContext);
        mNsdServiceAdapter = new NsdServiceAdapter(mContext);
        mInterestAdapter = new PendingInterestAdapter(mContext);

        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(OpportunisticDaemon.STARTED);
        mIntentFilter.addAction(OpportunisticDaemon.STOPPING);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mContext.registerReceiver(mBr, mIntentFilter);

        mNsdServiceDiscoverer = NsdServiceDiscoverer.getInstance();
        mNsdServiceDiscoverer.addObserver(this);

        mPeerTracker.addObserver(this);


        mBinding = FragmentOppPeerTrackingBinding.inflate(getActivity().getLayoutInflater());
    }

    /** Fragment lifecycle method (see https://developer.android.com/guide/components/fragments.html) */
    @Override
    public void onDetach() {
        mHandler.removeCallbacks(mJndnProcessor);
        mPeerTracker.deleteObserver(this);
        mContext.unregisterReceiver(mBr);
        super.onDetach();
    }

    /** Fragment lifecycle method. See https://developer.android.com/guide/components/fragments.html */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        mBinding.buttonGroupFormation.setOnClickListener(this);
        mBinding.buttonGroupLeave.setOnClickListener(this);

        mBinding.btnStartPeerDiscovery.setOnClickListener(this);
        mBinding.listWifiP2pPeers.setAdapter(mPeerAdapter);

        mBinding.listNsdServices.setAdapter(mNsdServiceAdapter);

        mBinding.listPendingInterests.setAdapter(mInterestAdapter);
        mBinding.listPendingInterests.setOnItemClickListener(this);

        return mBinding.getRoot();
    }

    /** Fragment lifecycle method (see https://developer.android.com/guide/components/fragments.html) */
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_start_peer_discovery:
                mWifiP2pManager.discoverPeers(mWifiP2pChannel, new OperationResult(TAG, "Peer Discovery"));
                break;
            case R.id.button_group_formation:
                mWifiP2pConnectivityManager.join(mPeerTracker.getPeers());
                break;
            case R.id.button_group_leave:
                mWifiP2pConnectivityManager.leave();
                break;
        }
    }

    /** Updates the Opportunistic Peer ListView */
    private Runnable mPeerUpdater = new Runnable() {
        @Override
        public void run() {
            mPeerAdapter.clear();
            mPeerAdapter.addAll(mPeers.values());
        }
    };

    private Runnable mServiceUpdater = new Runnable() {
        @Override
        public void run() {
            mNsdServiceAdapter.clear();
            mNsdServiceAdapter.addAll(mServices.values());
        }
    };

    /** Callback that the OpportunisticPeerTracker uses to notify of changes in the peer list
     * @param observable the OpportunisticPeerTracker
     * @param obj ignored
     */
    @Override
    public void update(Observable observable, Object obj) {
        FragmentActivity act = getActivity();

        if(observable instanceof OpportunisticPeerTracker) {
            Log.i(TAG, "observable instanceof OpportunisticPeerTracker");
            /* When the PeerTracker notifies of some changes to its list, retrieve the new list of Peers
            and use it to update the UI accordingly. */
            mPeers.clear();
            mPeers.putAll(mPeerTracker.getPeers());
     

            mCanFormGroup = !mWifiP2pConnectivityManager.isAspiringGroupOwner(mPeers);
            mBinding.buttonGroupFormation.setEnabled(mCanFormGroup && !mIsConnectedToGroup);

            //mPeerAdapter.clear();
            //mPeerAdapter.addAll(mPeers.values());


            if (act != null) {
                //Log.e(TAG, "ENTREI");
                act.runOnUiThread(mPeerUpdater);
            }

        } else if (observable instanceof NsdServiceDiscoverer) {
            /* When the NSD Service Tracker notifies of some changes to its list, retrieve the new list of services
               and use it to update the UI accordingly. */
            Log.i(TAG, "observable instanceof NsdServiceDiscoverer");
            if(obj != null) {
                NsdService svc = (NsdService) obj;
                mServices.put(svc.getUuid(), svc);
            } else {
                mServices.clear();
                mServices.putAll(mServiceTracker.getServices());
            }

            //mNsdServiceAdapter.clear();
            //mNsdServiceAdapter.addAll(mServices.values());


            if(act != null)
                act.runOnUiThread(mServiceUpdater);

        }
    }

    public Face getFace() {
        return mFace;
    }

    // jNDN requires a regular polling otherwise nothing happens. This handler takes care of it.
    private Handler mHandler = new Handler();
    private Runnable mJndnProcessor = new Runnable() {
        @Override
        public void run() {
            new JndnProcessEventTask(mFace).execute();
            mHandler.postDelayed(mJndnProcessor, PROCESS_INTERVAL);
        }
    };

    // Used to toggle the visibility of the ProgressBar based on whether peer discovery is running
    private BroadcastReceiver mBr = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, "Received Intent : " + action);
            if(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                int extra = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                if(extra == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
                    mBinding.discoveryInProgress.setVisibility(View.VISIBLE);
                else
                    mBinding.discoveryInProgress.setVisibility(View.INVISIBLE);
            } else if (OpportunisticDaemon.STARTED.equals(action)) {
                mPeerTracker.enable(context);
                mFace = new Face("127.0.0.1", 6363);
                new RegisterPrefixTask(mFace, PREFIX, OpportunisticPeerTracking.this, OpportunisticPeerTracking.this).execute();
                new RegisterPrefixForPushedDataTask(mFace, EMERGENCY, OpportunisticPeerTracking.this, OpportunisticPeerTracking.this).execute();
                mHandler.postDelayed(mJndnProcessor, PROCESS_INTERVAL);
            } else if (OpportunisticDaemon.STOPPING.equals(action)) {
                mPeerTracker.disable();
                mHandler.removeCallbacks(mJndnProcessor);
                mFace = null;
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo netInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                boolean wifiP2pConnected = netInfo.isConnected();
                Log.v(TAG, "Connection changed : " + (wifiP2pConnected ? "CONNECTED" : "DISCONNECTED"));

                mIsConnectedToGroup = wifiP2pConnected;
                mBinding.buttonGroupLeave.setEnabled(mIsConnectedToGroup);
                mBinding.buttonGroupFormation.setEnabled(!mIsConnectedToGroup);
            }
        }
    };

    @Override
    public void onInterest(Name prefix, final Interest interest, Face face, long interestFilterId, InterestFilter filter) {
        Log.v(TAG, "Received Interest : " + interest.getName() + " [" + interest.getInterestLifetimeMilliseconds() + "]");
        mPendingInterests.add(interest);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mInterestAdapter.add(interest);
            }
        });
    }

    /** Used when the RespondToInterestDialog terminates
     * @param interest the Interest for which the Dialog was used to respond
     */
    public void respondedToInterest(final Interest interest) {
        if(!interest.isLongLived()) {
            mPendingInterests.remove(interest);
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mInterestAdapter.remove(interest);
                }
            });
        }
    }

    /** jNDN callback to confirm successful registration of a prefix
     * @param prefix the Prefix that was registered
     * @param registeredPrefixId an internal ID which can be used to unregister the prefix
     */
    @Override
    public void onRegisterSuccess(Name prefix, long registeredPrefixId) {
        Log.v(TAG, "Registration Success : " + prefix.toString());
    }

    /** jNDN callback to notify Data was received in response to an Interest
     * @param interest the Interest for which the Data matches
     * @param data the Data which matches the Interest
     */
    @Override
    public void onData(Interest interest, Data data) {
        Log.v(TAG, "Received Data : " + data.getName().toString() + " > " + data.getContent().toString());
        DisplayDataDialog dialog = DisplayDataDialog.create(data);
        dialog.show(getChildFragmentManager(), dialog.getTag());
    }

    /** jNDN callback to notify when PushedData is received
     * @param data the Data packet received
     */
    @Override
    public void onPushedData(Data data) {
        Log.v(TAG, "Push Data Received : " + data.getName().toString());
        DisplayDataDialog dialog = DisplayDataDialog.create(data);
        dialog.show(getChildFragmentManager(), dialog.getTag());
    }

    /** Used to Respond to an Interest from the list of received Interests.
     * @param adapterView
     * @param view view in the list that was clicked
     * @param position item in the list that was clicked
     * @param l
     */
    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long l) {
        Interest si = mPendingInterests.get(position);
        DialogFragment dialog = RespondToInterestDialog.create(OpportunisticPeerTracking.this, mFace, si);
        dialog.show(getChildFragmentManager(), dialog.getTag());
    }

    /** Used to nicely display the Peers in a ListView */
    private class OpportunisticPeerAdapter extends ArrayAdapter<OpportunisticPeer> {
        private LayoutInflater mInflater;

        OpportunisticPeerAdapter(@NonNull Context context) {
            super(context, R.layout.item_ndn_opp_peer);
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ItemNdnOppPeerBinding inopb = ItemNdnOppPeerBinding.inflate(mInflater, parent, false);
            List<OpportunisticPeer> peers = new ArrayList<>(mPeers.values());
            inopb.setPeer(peers.get(position));
            return inopb.getRoot();
        }
    }
}
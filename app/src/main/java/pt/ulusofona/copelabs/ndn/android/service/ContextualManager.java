/**
 *  @version 1.0
 * COPYRIGHTS COPELABS/ULHT, LGPLv3.0, 2017-02-14
 * A dummy implementation of the ContextualManager that will be provided by Senseption, Lda.
 * @author Seweryn Dynerowicz (COPELABS/ULHT)
 */
package pt.ulusofona.copelabs.ndn.android.service;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.UUID;

import pt.ulusofona.copelabs.ndn.android.UmobileService;

class ContextualManager implements Observer {
    private static final String PROPERTY_UUID_KEY = "UMOBILE_UUID";
    private String mUmobileUuid;

    private Routing mRouting;
    private ServiceTracker mTracker;
    private Map<String, UmobileService> mServicePeers;

    ContextualManager(Context ctxt, Routing rt) {
        mRouting = rt;
        mUmobileUuid = obtainUmobileUuid(ctxt);
        mTracker = new ServiceTracker(ctxt, mUmobileUuid);
        mServicePeers = new HashMap<>();
    }

    private String obtainUmobileUuid(Context ctxt) {
        SharedPreferences storage = ctxt.getSharedPreferences(ContextualManager.class.getCanonicalName(), Context.MODE_PRIVATE);
        String uuid;
        if(!storage.contains(PROPERTY_UUID_KEY)) {
            uuid = UUID.randomUUID().toString();
            SharedPreferences.Editor editor = storage.edit();
            editor.putString(PROPERTY_UUID_KEY, mUmobileUuid);
            editor.apply();
        } else
            uuid = storage.getString(PROPERTY_UUID_KEY, null);

        return uuid;
    }

    String getUmobileUuid() { return mUmobileUuid; }
    List<UmobileService> getUmobilePeers() {
        return new ArrayList<>(mServicePeers.values());
    }

    public void enable() {
        mTracker.enable();
        mTracker.addObserver(this);
    }

    public void disable() {
        mTracker.deleteObserver(this);
        mTracker.disable();
    }

    @Override
    public void update(Observable observable, Object o) {
        // Construct delta between previous UmobileService list and the new one.
        Set<UmobileService> changes = new HashSet<>();

        Map<String, UmobileService> newServiceList = mTracker.mServices;
        for(String svcName : newServiceList.keySet()) {
            UmobileService newEntry = newServiceList.get(svcName);
            UmobileService oldEntry = mServicePeers.containsKey(svcName) ? mServicePeers.get(svcName) : null;

            if(! newEntry.equals(oldEntry) ) {
                mServicePeers.put(svcName, newEntry);
                changes.add(newEntry);
            }
        }

        // Push changes to Routing.
        mRouting.update(changes);
    }
}

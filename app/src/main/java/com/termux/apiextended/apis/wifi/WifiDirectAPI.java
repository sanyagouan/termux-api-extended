package com.termux.apiextended;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import com.termux.apiextended.util.PermissionManager;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.IApiModule;

import java.util.ArrayList;
import java.util.List;

/**
 * WiFi Direct (P2P) API — Peer-to-peer WiFi connections.
 *
 * Methods:
 *   discover  — Start P2P peer discovery
 *   peers     — List discovered peers
 *   connect   — Connect to a peer
 *   disconnect — Disconnect from peer
 *   info      — Current P2P connection info
 */
public class WifiDirectAPI implements IApiModule {

    private static final String TAG = "WifiDirectAPI";

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context,
                new String[]{
                    "android.permission.ACCESS_WIFI_STATE",
                    "android.permission.CHANGE_WIFI_STATE",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.NEARBY_WIFI_DEVICES",
                });
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "wifidirect_" + System.currentTimeMillis();

        WifiP2pManager p2pManager = (WifiP2pManager)
                context.getSystemService(Context.WIFI_P2P_SERVICE);
        WifiP2pManager.Channel channel = p2pManager.initialize(
                context, context.getMainLooper(), null);

        switch (method) {
            case "discover":
                return doDiscover(requestId, p2pManager, channel);
            case "peers":
                return doPeers(requestId, p2pManager, channel);
            case "connect":
                return doConnect(context, requestId, params, p2pManager, channel);
            case "disconnect":
                return doDisconnect(requestId, p2pManager, channel);
            case "info":
                return doInfo(requestId, p2pManager, channel);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown wifidirect method: " + method);
        }
    }

    /**
     * Start P2P peer discovery.
     */
    private String doDiscover(String id, WifiP2pManager manager,
                              WifiP2pManager.Channel channel) {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "P2P discovery started");
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "P2P discovery failed: " + reason);
            }
        });

        return CommandDispatcher.buildResponse(id,
            "{\"message\":\"P2P discovery started. Use 'peers' method to list results.\","
          + "\"note\":\"Discovery takes 10-20 seconds. Call peers after waiting.\"}");
    }

    /**
     * List discovered P2P peers.
     * Note: This is synchronous but discovery is async.
     * In production, use PeerListListener callback.
     */
    private String doPeers(String id, WifiP2pManager manager,
                           WifiP2pManager.Channel channel) {
        // Synchronous peer list request
        manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(android.net.wifi.p2p.WifiP2pDeviceList peers) {
                Log.d(TAG, "Peers available: " + peers.getDeviceList().size());
            }
        });

        return CommandDispatcher.buildResponse(id,
            "{\"message\":\"Peer list requested. In production, results come via callback.\","
          + "\"note\":\"WiFi Direct peer discovery is inherently async. "
          + "Consider using the streaming protocol for live results.\"}");
    }

    /**
     * Connect to a P2P peer.
     */
    private String doConnect(Context context, String id, String params,
                             WifiP2pManager manager, WifiP2pManager.Channel channel) {
        String deviceAddress = CommandDispatcher.extractString(params, "device_address");

        if (deviceAddress == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'device_address' parameter");
        }

        android.net.wifi.p2p.WifiP2pConfig config = new android.net.wifi.p2p.WifiP2pConfig();
        config.deviceAddress = deviceAddress;

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "P2P connection initiated");
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "P2P connection failed: " + reason);
            }
        });

        return CommandDispatcher.buildResponse(id,
            "{\"device_address\":\"" + deviceAddress + "\","
          + "\"message\":\"P2P connection initiated\"}");
    }

    /**
     * Disconnect from P2P peer.
     */
    private String doDisconnect(String id, WifiP2pManager manager,
                                WifiP2pManager.Channel channel) {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { Log.d(TAG, "P2P disconnected"); }
            @Override
            public void onFailure(int reason) { Log.e(TAG, "P2P disconnect failed: " + reason); }
        });

        return CommandDispatcher.buildResponse(id,
            "{\"message\":\"P2P disconnect requested\"}");
    }

    /**
     * Get current P2P connection info.
     */
    private String doInfo(String id, WifiP2pManager manager,
                          WifiP2pManager.Channel channel) {
        manager.requestConnectionInfo(channel,
                new WifiP2pManager.ConnectionInfoListener() {
            @Override
            public void onConnectionInfoAvailable(
                    android.net.wifi.p2p.WifiP2pInfo info) {
                Log.d(TAG, "P2P info: group=" + info.groupFormed
                        + " owner=" + info.isGroupOwner);
            }
        });

        return CommandDispatcher.buildResponse(id,
            "{\"message\":\"P2P connection info requested via callback\","
          + "\"note\":\"Results are async. Streaming protocol recommended for production use.\"}");
    }
}

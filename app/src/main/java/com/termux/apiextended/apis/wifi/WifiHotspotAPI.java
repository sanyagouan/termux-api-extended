package com.termux.apiextended.apis.wifi;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.util.PermissionManager;

import java.lang.reflect.Method;

/**
 * WiFi Hotspot API — Manage WiFi tethering/hotspot.
 *
 * Methods:
 *   enable   — Start hotspot with SSID, password, band
 *   disable  — Stop hotspot
 *   status   — Current hotspot state
 *   clients  — List connected clients (where available)
 *
 * Note: Hotspot control requires CHANGE_NETWORK_STATE which may need
 * special handling on some Android versions. Uses reflection for
 * ConnectivityManager.startTethering() as it's a hidden API.
 */
public class WifiHotspotAPI implements IApiModule {

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.HOTSPOT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "hotspot_" + System.currentTimeMillis();

        switch (method) {
            case "enable":
                return doEnable(context, requestId, params);
            case "disable":
                return doDisable(context, requestId);
            case "status":
                return doStatus(context, requestId);
            case "clients":
                return doClients(context, requestId);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown hotspot method: " + method);
        }
    }

    /**
     * Enable WiFi hotspot via WifiManager.setWifiApEnabled() (legacy)
     * or ConnectivityManager.startTethering() (modern).
     */
    @SuppressWarnings("deprecation")
    private String doEnable(Context context, String id, String params) {
        String ssid = CommandDispatcher.extractString(params, "ssid");
        String password = CommandDispatcher.extractString(params, "password");
        String band = CommandDispatcher.extractString(params, "band"); // "2.4", "5"
        int timeout = CommandDispatcher.extractInt(params, "timeout", 0); // 0 = permanent

        if (ssid == null || ssid.isEmpty()) {
            ssid = "TermuxHotspot";
        }

        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        try {
            // Try modern approach first (Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return enableModern(context, id, ssid, password, band);
            }

            // Legacy approach: WifiManager.setWifiApEnabled via reflection
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = ssid;
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);

            if (password != null && password.length() >= 8) {
                config.preSharedKey = "\"" + password + "\"";
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK);
            } else {
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }

            // Try to set band via reflection
            if ("5".equals(band)) {
                try {
                    config.apBand = WifiConfiguration.AP_BAND_5GHZ;
                } catch (Exception e) {
                    // apBand may not be available
                }
            }

            Method setWifiApEnabled = wm.getClass().getMethod(
                    "setWifiApEnabled", WifiConfiguration.class, boolean.class);
            setWifiApEnabled.invoke(wm, config, true);

            return CommandDispatcher.buildResponse(id,
                "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
              + "\"band\":\"" + (band != null ? band : "2.4") + "\","
              + "\"encrypted\":" + (password != null && password.length() >= 8) + ","
              + "\"method\":\"reflection\","
              + "\"message\":\"Hotspot enabling (may take a few seconds)\"}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_ENABLE_FAILED",
                    "Failed to enable hotspot: " + e.getMessage());
        }
    }

    /**
     * Modern approach using ConnectivityManager.startTethering().
     * Uses reflection as the API is hidden.
     */
    private String enableModern(Context context, String id, String ssid,
                                String password, String band) {
        try {
            Object connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Class<?> cmClass = connectivityManager.getClass();

            Method startTethering = cmClass.getMethod(
                    "startTethering", int.class, boolean.class,
                    android.net.ConnectivityManager.OnStartTetheringCallback.class);

            // Use a simple callback
            startTethering.invoke(connectivityManager,
                    0, // TETHERING_WIFI
                    false,
                    new android.net.ConnectivityManager.OnStartTetheringCallback() {
                        @Override
                        public void onTetheringStarted() {}
                        @Override
                        public void onTetheringFailed() {}
                    });

            return CommandDispatcher.buildResponse(id,
                "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
              + "\"method\":\"ConnectivityManager\","
              + "\"message\":\"Hotspot enabling via ConnectivityManager\"}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_ENABLE_FAILED",
                    "Modern tethering failed: " + e.getMessage()
                    + ". Try enabling hotspot from Settings first.");
        }
    }

    /**
     * Disable WiFi hotspot.
     */
    @SuppressWarnings("deprecation")
    private String doDisable(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Modern: stopTethering
                Object cm = context.getSystemService(Context.CONNECTIVITY_CLASS);
                cm.getClass().getMethod("stopTethering", int.class).invoke(cm, 0);
            } else {
                // Legacy: setWifiApEnabled(null, false)
                Method setWifiApEnabled = wm.getClass().getMethod(
                        "setWifiApEnabled", WifiConfiguration.class, boolean.class);
                setWifiApEnabled.invoke(wm, null, false);
            }

            return CommandDispatcher.buildResponse(id,
                "{\"message\":\"Hotspot disabled\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_DISABLE_FAILED", e.getMessage());
        }
    }

    /**
     * Get hotspot status.
     */
    @SuppressWarnings("deprecation")
    private String doStatus(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            Method isWifiApEnabled = wm.getClass().getMethod("isWifiApEnabled");
            boolean enabled = (Boolean) isWifiApEnabled.invoke(wm);

            Method getWifiApState = wm.getClass().getMethod("getWifiApState");
            int state = (Integer) getWifiApState.invoke(wm);

            String stateStr;
            switch (state) {
                case 13: stateStr = "enabled"; break;
                case 14: stateStr = "disabled"; break;
                case 12: stateStr = "enabling"; break;
                case 11: stateStr = "disabling"; break;
                case 10: stateStr = "failed"; break;
                default: stateStr = "unknown(" + state + ")"; break;
            }

            // Get hotspot configuration
            Method getWifiApConfiguration = wm.getClass().getMethod("getWifiApConfiguration");
            WifiConfiguration config = (WifiConfiguration) getWifiApConfiguration.invoke(wm);

            String configJson = "null";
            if (config != null) {
                configJson = "{"
                    + "\"ssid\":\"" + PermissionManager.escapeJson(
                            config.SSID != null ? config.SSID.replace("\"","") : "unknown") + "\""
                    + "}";
            }

            return CommandDispatcher.buildResponse(id,
                "{\"enabled\":" + enabled + ","
              + "\"state\":\"" + stateStr + "\","
              + "\"configuration\":" + configJson + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_STATUS_FAILED", e.getMessage());
        }
    }

    /**
     * List clients connected to hotspot.
     * Uses reflection on WifiManager.getClientList() or similar.
     */
    private String doClients(Context context, String id) {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

            // Try getClientMacList (available on some ROMs)
            try {
                Method getClientList = wm.getClass().getMethod("getClientMacList");
                @SuppressWarnings("unchecked")
                java.util.List<android.net.wifi.WifiClient> clients =
                        (java.util.List<android.net.wifi.WifiClient>) getClientList.invoke(wm);

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (android.net.wifi.WifiClient client : clients) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"mac_address\":\"")
                      .append(PermissionManager.escapeJson(client.getMacAddress().toString()))
                      .append("\"}");
                }
                sb.append("]");

                return CommandDispatcher.buildResponse(id,
                    "{\"clients\":" + sb.toString() + ","
                  + "\"count\":" + clients.size() + "}");
            } catch (NoSuchMethodException e) {
                // Try DHCP lease info as fallback
                DhcpInfo dhcp = wm.getDhcpInfo();
                return CommandDispatcher.buildResponse(id,
                    "{\"clients\":[],"
                  + "\"count\":0,"
                  + "\"note\":\"Client listing not available on this ROM. DHCP info available.\"}");
            }
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "CLIENTS_FAILED", e.getMessage());
        }
    }
}

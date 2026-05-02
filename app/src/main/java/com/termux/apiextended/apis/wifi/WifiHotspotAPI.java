package com.termux.apiextended.apis.wifi;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.util.PermissionManager;

import java.lang.reflect.Method;
import java.util.List;

/**
 * WiFi Hotspot API — Manage WiFi tethering/hotspot.
 *
 * Methods:
 *   enable   — Start hotspot with SSID, password, band
 *   disable  — Stop hotspot
 *   status   — Current hotspot state
 *   clients  — List connected clients (where available)
 *
 * Note: On Android 11+ (API 30+), hotspot control is restricted for non-system apps.
 * Uses startLocalOnlyHotspot for modern Android, reflection for legacy APIs.
 * WifiConfiguration references use reflection as the class was removed from SDK 30+.
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

    private String doEnable(Context context, String id, String params) {
        String ssid = CommandDispatcher.extractString(params, "ssid");
        String password = CommandDispatcher.extractString(params, "password");
        String band = CommandDispatcher.extractString(params, "band");
        int timeout = CommandDispatcher.extractInt(params, "timeout", 0);

        if (ssid == null || ssid.isEmpty()) ssid = "TermuxHotspot";

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return enableLocalOnly(context, id, ssid, wm);
            } else {
                return enableLegacy(id, ssid, password, band, wm);
            }
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_ENABLE_FAILED",
                    "Failed: " + e.getMessage());
        }
    }

    /**
     * API 30+: Use startLocalOnlyHotspot for local-only AP.
     * Custom SSID/password requires system permissions.
     */
    private String enableLocalOnly(Context context, String id, String ssid, WifiManager wm) {
        wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {}
            @Override
            public void onStopped() {}
            @Override
            public void onFailed(int reason) {}
        }, null);

        return CommandDispatcher.buildResponse(id,
            "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
          + "\"method\":\"LocalOnlyHotspot\","
          + "\"message\":\"Local-only hotspot requested. Custom SSID/password requires system permissions on Android 11+.\"}");
    }

    /**
     * Legacy: Use reflection for WifiManager.setWifiApEnabled with WifiConfiguration.
     * WifiConfiguration was removed from SDK 30+ so we can't reference it directly.
     */
    private String enableLegacy(String id, String ssid, String password, String band, WifiManager wm) {
        try {
            Class<?> wcClass = Class.forName("android.net.wifi.WifiConfiguration");
            Object config = wcClass.newInstance();

            wcClass.getField("SSID").set(config, ssid);

            if (password != null && password.length() >= 8) {
                wcClass.getField("preSharedKey").set(config, "\"" + password + "\"");
                Class<?> kmClass = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
                Object wpa2psk = kmClass.getField("WPA2_PSK").get(null);
                Object allowedKM = wcClass.getField("allowedKeyManagement").get(config);
                allowedKM.getClass().getMethod("set", int.class).invoke(allowedKM, wpa2psk);
            } else {
                Class<?> kmClass = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
                Object none = kmClass.getField("NONE").get(null);
                Object allowedKM = wcClass.getField("allowedKeyManagement").get(config);
                allowedKM.getClass().getMethod("set", int.class).invoke(allowedKM, none);
            }

            Method setWifiApEnabled = wm.getClass().getMethod("setWifiApEnabled", wcClass, boolean.class);
            setWifiApEnabled.invoke(wm, config, true);

            return CommandDispatcher.buildResponse(id,
                "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
              + "\"band\":\"" + (band != null ? band : "2.4") + "\","
              + "\"method\":\"reflection\","
              + "\"message\":\"Hotspot enabling via legacy API\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_ENABLE_FAILED",
                    "Legacy hotspot failed: " + e.getMessage());
        }
    }

    private String doDisable(Context context, String id) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object cm = context.getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.getClass().getMethod("stopTethering", int.class).invoke(cm, 0);
            } else {
                Class<?> wcClass = Class.forName("android.net.wifi.WifiConfiguration");
                Method setWifiApEnabled = wm.getClass().getMethod("setWifiApEnabled", wcClass, boolean.class);
                setWifiApEnabled.invoke(wm, null, false);
            }

            return CommandDispatcher.buildResponse(id,
                "{\"message\":\"Hotspot disabled\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_DISABLE_FAILED", e.getMessage());
        }
    }

    private String doStatus(Context context, String id) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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

            String configJson = "null";
            try {
                Method getWifiApConfiguration = wm.getClass().getMethod("getWifiApConfiguration");
                Object config = getWifiApConfiguration.invoke(wm);
                if (config != null) {
                    String configSsid = (String) config.getClass().getField("SSID").get(config);
                    configJson = "{\"ssid\":\"" + PermissionManager.escapeJson(
                            configSsid != null ? configSsid.replace("\"", "") : "unknown") + "\"}";
                }
            } catch (Exception ignored) {}

            return CommandDispatcher.buildResponse(id,
                "{\"enabled\":" + enabled + ","
              + "\"state\":\"" + stateStr + "\","
              + "\"configuration\":" + configJson + "}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HOTSPOT_STATUS_FAILED", e.getMessage());
        }
    }

    private String doClients(Context context, String id) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

            try {
                Method getClientList = wm.getClass().getMethod("getClientMacList");
                @SuppressWarnings("unchecked")
                java.util.List<Object> clients = (java.util.List<Object>) getClientList.invoke(wm);

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object client : clients) {
                    if (!first) sb.append(",");
                    first = false;
                    try {
                        Object mac = client.getClass().getMethod("getMacAddress").invoke(client);
                        sb.append("{\"mac_address\":\"").append(PermissionManager.escapeJson(mac.toString())).append("\"}");
                    } catch (Exception e2) {
                        sb.append("{\"mac_address\":\"unknown\"}");
                    }
                }
                sb.append("]");

                return CommandDispatcher.buildResponse(id,
                    "{\"clients\":" + sb.toString() + ","
                  + "\"count\":" + clients.size() + "}");
            } catch (NoSuchMethodException e) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                return CommandDispatcher.buildResponse(id,
                    "{\"clients\":[],"
                  + "\"count\":0,"
                  + "\"note\":\"Client listing not available on this ROM.\"}");
            }
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "CLIENTS_FAILED", e.getMessage());
        }
    }
}

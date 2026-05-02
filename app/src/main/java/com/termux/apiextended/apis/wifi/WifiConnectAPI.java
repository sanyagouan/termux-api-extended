package com.termux.apiextended.apis.wifi;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;

import com.termux.apiextended.util.PermissionManager;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.IApiModule;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * WiFi Connect API — Connect/disconnect from WiFi networks.
 *
 * Methods:
 *   connect    — Connect to SSID with password
 *   disconnect — Disconnect current network
 *   forget     — Remove saved network
 *   reassociate — Force reconnect to current network
 *   config     — Current IP/DNS/proxy configuration
 *
 * WifiConfiguration is accessed via reflection since it was removed from SDK 30+.
 * Modern Android 10+ uses WifiNetworkSuggestion.
 */
public class WifiConnectAPI implements IApiModule {

    private static final String TAG = "WifiConnectAPI";

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.WIFI_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "wifi_" + System.currentTimeMillis();

        switch (method) {
            case "connect":
                return doConnect(context, requestId, params);
            case "disconnect":
                return doDisconnect(context, requestId);
            case "forget":
                return doForget(context, requestId, params);
            case "reassociate":
                return doReassociate(context, requestId);
            case "config":
                return doGetConfig(context, requestId);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown wifi method: " + method);
        }
    }

    private String doConnect(Context context, String id, String params) {
        String ssid = CommandDispatcher.extractString(params, "ssid");
        String password = CommandDispatcher.extractString(params, "password");
        String security = CommandDispatcher.extractString(params, "security");
        boolean hidden = CommandDispatcher.extractBoolean(params, "hidden", false);

        if (ssid == null || ssid.isEmpty()) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'ssid' parameter");
        }

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return connectViaSuggestion(context, id, ssid, password, security, hidden);
        } else {
            return connectViaReflection(context, id, ssid, password, security, hidden);
        }
    }

    /**
     * Android 10+: WifiNetworkSuggestion (officially supported).
     */
    private String connectViaSuggestion(Context context, String id, String ssid, String password,
                                         String security, boolean hidden) {
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);

            // setIsHiddenSsid added in API 31
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setIsHiddenSsid(hidden);
            }

            if (password != null && !password.isEmpty()) {
                if ("WPA3".equalsIgnoreCase(security)) {
                    builder.setWpa3Passphrase(password);
                } else {
                    builder.setWpa2Passphrase(password);
                }
            }

            List<WifiNetworkSuggestion> suggestions = Collections.singletonList(builder.build());
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int status = wm.addNetworkSuggestions(suggestions);

            String statusMsg;
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                statusMsg = "Suggestion added. Device will connect automatically if in range.";
            } else if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                statusMsg = "Network suggestion already exists.";
            } else {
                statusMsg = "Failed to add suggestion. Status: " + status;
            }

            return CommandDispatcher.buildResponse(id,
                "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
              + "\"security\":\"" + PermissionManager.escapeJson(security != null ? security : "OPEN") + "\","
              + "\"method\":\"WifiNetworkSuggestion\","
              + "\"status_code\":" + status + ","
              + "\"message\":\"" + PermissionManager.escapeJson(statusMsg) + "\"}");

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "CONNECT_FAILED", e.getMessage());
        }
    }

    /**
     * Legacy (API <29): Use reflection for WifiManager.addNetwork(WifiConfiguration).
     */
    private String connectViaReflection(Context context, String id, String ssid,
                                         String password, String security, boolean hidden) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Class<?> wcClass = Class.forName("android.net.wifi.WifiConfiguration");
            Object config = wcClass.newInstance();

            wcClass.getField("SSID").set(config, "\"" + ssid + "\"");
            wcClass.getField("hiddenSSID").set(config, hidden);

            if (password == null || password.isEmpty()) {
                Class<?> kmClass = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
                Object none = kmClass.getField("NONE").get(null);
                Object allowedKM = wcClass.getField("allowedKeyManagement").get(config);
                allowedKM.getClass().getMethod("set", int.class).invoke(allowedKM, none);
            } else {
                wcClass.getField("preSharedKey").set(config, "\"" + password + "\"");
                Class<?> kmClass = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
                Object wpaPsk = kmClass.getField("WPA_PSK").get(null);
                Object allowedKM = wcClass.getField("allowedKeyManagement").get(config);
                allowedKM.getClass().getMethod("set", int.class).invoke(allowedKM, wpaPsk);
            }

            Method addNetwork = wm.getClass().getMethod("addNetwork", wcClass);
            int networkId = (Integer) addNetwork.invoke(wm, config);
            if (networkId == -1) {
                return CommandDispatcher.buildError(id, "ADD_NETWORK_FAILED",
                        "Failed to add network configuration");
            }

            wm.enableNetwork(networkId, true);
            wm.reconnect();

            return CommandDispatcher.buildResponse(id,
                "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
              + "\"network_id\":" + networkId + ","
              + "\"method\":\"WifiManager.addNetwork\","
              + "\"message\":\"Network added and reconnecting\"}");

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "CONNECT_FAILED", e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private String doDisconnect(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            wm.disconnect();
            return CommandDispatcher.buildResponse(id,
                "{\"message\":\"Disconnected from WiFi\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "DISCONNECT_FAILED", e.getMessage());
        }
    }

    /**
     * Remove a saved network configuration.
     * On Android 10+ this requires system permissions.
     */
    private String doForget(Context context, String id, String params) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String ssid = CommandDispatcher.extractString(params, "ssid");
        int networkId = CommandDispatcher.extractInt(params, "network_id", -1);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return CommandDispatcher.buildError(id, "NOT_SUPPORTED",
                        "Network management requires system permissions on Android 10+. "
                      + "Remove saved networks via Android Settings.");
            }

            // Legacy path via reflection
            if (networkId != -1) {
                wm.removeNetwork(networkId);
                wm.saveConfiguration();
                return CommandDispatcher.buildResponse(id,
                    "{\"network_id\":" + networkId + ",\"removed\":true}");
            } else if (ssid != null) {
                Method getConfiguredNetworks = WifiManager.class.getMethod("getConfiguredNetworks");
                @SuppressWarnings("unchecked")
                java.util.List<?> configs = (java.util.List<?>) getConfiguredNetworks.invoke(wm);
                Class<?> wcClass = Class.forName("android.net.wifi.WifiConfiguration");
                boolean found = false;
                for (Object cfg : configs) {
                    String cfgSsid = (String) wcClass.getField("SSID").get(cfg);
                    int cfgNetId = wcClass.getField("networkId").getInt(cfg);
                    if (cfgSsid != null && cfgSsid.equals("\"" + ssid + "\"")) {
                        wm.removeNetwork(cfgNetId);
                        found = true;
                    }
                }
                if (found) {
                    wm.saveConfiguration();
                    return CommandDispatcher.buildResponse(id,
                        "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\",\"removed\":true}");
                } else {
                    return CommandDispatcher.buildError(id, "NOT_FOUND",
                            "No saved network found with SSID: " + ssid);
                }
            } else {
                return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                        "Provide 'ssid' or 'network_id'");
            }
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "FORGET_FAILED", e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private String doReassociate(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            wm.reassociate();
            return CommandDispatcher.buildResponse(id,
                "{\"message\":\"Reassociating with current network\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "REASSOCIATE_FAILED", e.getMessage());
        }
    }

    private String doGetConfig(Context context, String id) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wm.getDhcpInfo();
            java.net.InetAddress address = intToInetAddress(dhcp.ipAddress);
            java.net.InetAddress gateway = intToInetAddress(dhcp.gateway);
            java.net.InetAddress netmask = intToInetAddress(dhcp.netmask);
            java.net.InetAddress dns1 = intToInetAddress(dhcp.dns1);
            java.net.InetAddress dns2 = intToInetAddress(dhcp.dns2);

            String dnsList = "[";
            if (dns1 != null) dnsList += "\"" + dns1.getHostAddress() + "\"";
            if (dns2 != null) {
                if (dns1 != null) dnsList += ",";
                dnsList += "\"" + dns2.getHostAddress() + "\"";
            }
            dnsList += "]";

            String proxyHost = android.net.Proxy.getHost(context);
            int proxyPort = android.net.Proxy.getPort(context);

            return CommandDispatcher.buildResponse(id,
                "{\"ip\":\"" + (address != null ? address.getHostAddress() : "unknown") + "\","
              + "\"gateway\":\"" + (gateway != null ? gateway.getHostAddress() : "unknown") + "\","
              + "\"netmask\":\"" + (netmask != null ? netmask.getHostAddress() : "unknown") + "\","
              + "\"dns\":" + dnsList + ","
              + "\"proxy\":{\"host\":\"" + PermissionManager.escapeJson(proxyHost != null ? proxyHost : "") + "\","
              + "\"port\":" + proxyPort + "},"
              + "\"lease_duration\":" + dhcp.leaseDuration + ","
              + "\"server_address\":\"" + (intToInetAddress(dhcp.serverAddress) != null ?
                    intToInetAddress(dhcp.serverAddress).getHostAddress() : "unknown") + "\"}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "CONFIG_FAILED", e.getMessage());
        }
    }

    private static java.net.InetAddress intToInetAddress(int addr) {
        try {
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                bytes[i] = (byte) ((addr >> (8 * (3 - i))) & 0xFF);
            }
            return java.net.InetAddress.getByAddress(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}

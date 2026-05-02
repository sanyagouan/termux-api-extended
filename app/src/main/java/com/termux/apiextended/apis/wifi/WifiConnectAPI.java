package com.termux.apiextended;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;

import com.termux.apiextended.util.PermissionManager;

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

    /**
     * Connect to a WiFi network by SSID and password.
     * Uses WifiNetworkSuggestion on Android 10+ (recommended way).
     * Falls back to WifiManager.addNetwork on older versions.
     */
    private String doConnect(Context context, String id, String params) {
        String ssid = CommandDispatcher.extractString(params, "ssid");
        String password = CommandDispatcher.extractString(params, "password");
        String security = CommandDispatcher.extractString(params, "security"); // "WPA2", "WPA3", "OPEN"
        boolean hidden = CommandDispatcher.extractBoolean(params, "hidden", false);

        if (ssid == null || ssid.isEmpty()) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'ssid' parameter");
        }

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (!wm.isWifiEnabled()) {
            wm.setWifiEnabled(true);
        }

        // Android 10+: Use WifiNetworkSuggestion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return connectViaSuggestion(context, id, ssid, password, security, hidden);
        } else {
            return connectViaWifiManager(wm, id, ssid, password, security, hidden);
        }
    }

    /**
     * Android 10+ approach: WifiNetworkSuggestion.
     * This is the officially supported way for non-system apps to connect to WiFi.
     * The OS handles the connection automatically once a matching network is in range.
     */
    private String connectViaSuggestion(Context context, String id, String ssid, String password,
                                         String security, boolean hidden) {
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsHiddenNetwork(hidden);

            if (password != null && !password.isEmpty()) {
                if ("WPA3".equalsIgnoreCase(security)) {
                    builder.setWpa3Passphrase(password);
                } else {
                    builder.setWpa2Passphrase(password);
                }
            }
            // If no password and no security -> OPEN network

            List<WifiNetworkSuggestion> suggestions = List.of(builder.build());
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int status = wm.addNetworkSuggestions(suggestions);

            String statusMsg;
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                statusMsg = "Suggestion added. If network is in range, device will connect automatically.";
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
     * Legacy approach for Android < 10: WifiManager.addNetwork().
     */
    @SuppressWarnings("deprecation")
    private String connectViaWifiManager(WifiManager wm, String id, String ssid,
                                          String password, String security, boolean hidden) {
        try {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.hiddenSSID = hidden;

            if (password == null || password.isEmpty()) {
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                config.preSharedKey = "\"" + password + "\"";
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            }

            int networkId = wm.addNetwork(config);
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

    /**
     * Disconnect from the current WiFi network.
     */
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
     */
    @SuppressWarnings("deprecation")
    private String doForget(Context context, String id, String params) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        String ssid = CommandDispatcher.extractString(params, "ssid");
        int networkId = CommandDispatcher.extractInt(params, "network_id", -1);

        try {
            if (networkId != -1) {
                boolean removed = wm.removeNetwork(networkId);
                wm.saveConfiguration();
                return CommandDispatcher.buildResponse(id,
                    "{\"network_id\":" + networkId + ","
                  + "\"removed\":" + removed + "}");
            } else if (ssid != null) {
                List<WifiConfiguration> configs = wm.getConfiguredNetworks();
                boolean found = false;
                for (WifiConfiguration cfg : configs) {
                    if (cfg.SSID != null && cfg.SSID.equals("\"" + ssid + "\"")) {
                        wm.removeNetwork(cfg.networkId);
                        found = true;
                    }
                }
                if (found) {
                    wm.saveConfiguration();
                    return CommandDispatcher.buildResponse(id,
                        "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
                      + "\"removed\":true}");
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

    /**
     * Force reconnection to the current WiFi network.
     */
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

    /**
     * Get current network configuration (IP, DNS, gateway, proxy).
     */
    private String doGetConfig(Context context, String id) {
        try {
            java.net.DhcpInfo dhcp = android.net.wifi.WifiManager.getDhcpInfo(context);
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

            // Get proxy settings
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

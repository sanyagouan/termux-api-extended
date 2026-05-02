package com.termux.apiextended.apis.wifi;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.util.PermissionManager;

import java.util.Collections;
import java.util.List;

/**
 * WiFi Networks API — Scan results, saved networks, signal monitoring.
 *
 * Methods:
 *   scan      — Scan visible networks (enhanced with security, channel, band info)
 *   saved     — List saved/configured networks
 *   info      — Detailed current connection info
 *   signals   — One-shot signal strength reading
 */
public class WifiNetworksAPI implements IApiModule {

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.WIFI_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "wifi_net_" + System.currentTimeMillis();

        switch (method) {
            case "scan":
                return doScan(context, requestId, params);
            case "saved":
                return doSaved(context, requestId);
            case "info":
                return doInfo(context, requestId);
            case "signals":
                return doSignals(context, requestId);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown wifi.networks method: " + method);
        }
    }

    /**
     * Enhanced WiFi scan with full details per network.
     */
    private String doScan(Context context, String id, String params) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        // Trigger a fresh scan
        wm.startScan();

        List<ScanResult> results = wm.getScanResults();
        if (results == null || results.isEmpty()) {
            return CommandDispatcher.buildError(id, "NO_RESULTS",
                    "No networks found. Ensure location is enabled.");
        }

        // Sort by signal strength (strongest first)
        Collections.sort(results, (a, b) -> b.level - a.level);

        // Optional SSID filter
        String filterSsid = CommandDispatcher.extractString(params, "ssid_filter");

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (ScanResult scan : results) {
            if (filterSsid != null && !scan.SSID.toLowerCase().contains(filterSsid.toLowerCase())) {
                continue;
            }
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"ssid\":\"").append(PermissionManager.escapeJson(scan.SSID)).append("\",");
            sb.append("\"bssid\":\"").append(scan.BSSID).append("\",");
            sb.append("\"rssi\":").append(scan.level).append(",");
            sb.append("\"frequency_mhz\":").append(scan.frequency).append(",");
            sb.append("\"channel\":").append(frequencyToChannel(scan.frequency)).append(",");
            sb.append("\"band\":\"").append(frequencyToBand(scan.frequency)).append("\",");
            sb.append("\"channel_width\":\"").append(channelWidthString(scan.channelWidth)).append("\",");

            // Parse capabilities
            String caps = scan.capabilities != null ? scan.capabilities : "";
            sb.append("\"security\":[");
            if (caps.contains("WPA3")) sb.append("\"WPA3\"");
            if (caps.contains("WPA2") || caps.contains("RSN")) {
                if (caps.contains("WPA3")) sb.append(",");
                sb.append("\"WPA2\"");
            }
            if (caps.contains("WPA") && !caps.contains("WPA2")) sb.append("\"WPA\"");
            if (caps.contains("WEP")) sb.append("\"WEP\"");
            if (caps.contains("ESS") && caps.length() <= 5) sb.append("\"OPEN\"");
            if (caps.contains("WPS")) sb.append(",\"WPS\"");
            sb.append("],");

            sb.append("\"encrypted\":").append(!caps.contains("ESS") || caps.length() > 4).append(",");
            sb.append("\"hidden\":").append(scan.SSID.isEmpty()).append(",");

            // Signal quality percentage (rough estimate)
            int quality = Math.max(0, Math.min(100, scan.level + 100));
            sb.append("\"quality_percent\":").append(quality).append(",");

            if (!TextUtils.isEmpty(scan.operatorFriendlyName)) {
                sb.append("\"venue\":\"").append(PermissionManager.escapeJson(scan.operatorFriendlyName.toString())).append("\",");
            }

            sb.append("\"timestamp\":").append(scan.timestamp);
            sb.append("}");
        }
        sb.append("]");

        return CommandDispatcher.buildResponse(id,
            "{\"networks\":" + sb.toString() + ","
          + "\"count\":" + (first ? 0 : 1) + "}");
    }

    /**
     * List all saved/configured WiFi networks.
     */
    @SuppressWarnings("deprecation")
    private String doSaved(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        List<WifiConfiguration> configs = wm.getConfiguredNetworks();

        if (configs == null || configs.isEmpty()) {
            return CommandDispatcher.buildResponse(id, "{\"networks\":[],\"count\":0}");
        }

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (WifiConfiguration cfg : configs) {
            if (!first) sb.append(",");
            first = false;

            String ssid = cfg.SSID != null ? cfg.SSID.replace("\"", "") : "unknown";
            String keyMgmt = cfg.allowedKeyManagement != null ? keyMgmtToString(cfg) : "UNKNOWN";
            String status = configStatusToString(cfg.status);

            sb.append("{");
            sb.append("\"network_id\":").append(cfg.networkId).append(",");
            sb.append("\"ssid\":\"").append(PermissionManager.escapeJson(ssid)).append("\",");
            sb.append("\"security\":\"").append(keyMgmt).append("\",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"hidden\":").append(cfg.hiddenSSID).append(",");
            sb.append("\"priority\":").append(cfg.priority);
            sb.append("}");
        }
        sb.append("]");

        return CommandDispatcher.buildResponse(id,
            "{\"networks\":" + sb.toString() + ","
          + "\"count\":" + configs.size() + "}");
    }

    /**
     * Detailed current connection info.
     */
    @SuppressWarnings("deprecation")
    private String doInfo(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();

        if (info == null) {
            return CommandDispatcher.buildError(id, "NOT_CONNECTED", "No active WiFi connection");
        }

        String ssid = info.getSSID() != null ? info.getSSID().replace("\"", "") : "unknown";
        String bssid = info.getBSSID();
        int rssi = info.getRssi();
        int speed = info.getLinkSpeed();
        int frequency = info.getFrequency();

        int quality = Math.max(0, Math.min(100, rssi + 100));
        String signalLevel;
        if (rssi >= -50) signalLevel = "excellent";
        else if (rssi >= -60) signalLevel = "good";
        else if (rssi >= -70) signalLevel = "fair";
        else signalLevel = "weak";

        // Get DHCP info
        DhcpInfo dhcp = wm.getDhcpInfo();
        String ip = intToIp(dhcp.ipAddress);
        String gateway = intToIp(dhcp.gateway);

        return CommandDispatcher.buildResponse(id,
            "{\"ssid\":\"" + PermissionManager.escapeJson(ssid) + "\","
          + "\"bssid\":\"" + (bssid != null ? bssid : "unknown") + "\","
          + "\"ip\":\"" + ip + "\","
          + "\"gateway\":\"" + gateway + "\","
          + "\"frequency_mhz\":" + frequency + ","
          + "\"channel\":" + frequencyToChannel(frequency) + ","
          + "\"band\":\"" + frequencyToBand(frequency) + "\","
          + "\"link_speed_mbps\":" + speed + ","
          + "\"rssi\":" + rssi + ","
          + "\"quality_percent\":" + quality + ","
          + "\"signal_level\":\"" + signalLevel + "\","
          + "\"network_id\":" + info.getNetworkId() + ","
          + "\"supplicant_state\":\"" + info.getSupplicantState() + "\","
          + "\"mac_address\":\"" + PermissionManager.escapeJson(info.getMacAddress()) + "\"}");
    }

    /**
     * One-shot signal reading (RSSI, link speed, noise).
     */
    @SuppressWarnings("deprecation")
    private String doSignals(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        int rssi = info.getRssi();
        int speed = info.getLinkSpeed();

        // Trigger scan for additional data
        wm.startScan();

        return CommandDispatcher.buildResponse(id,
            "{\"rssi\":" + rssi + ","
          + "\"link_speed_mbps\":" + speed + ","
          + "\"quality_percent\":" + Math.max(0, Math.min(100, rssi + 100)) + ","
          + "\"frequency_mhz\":" + info.getFrequency() + ","
          + "\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    // --- Utility methods ---

    private static int frequencyToChannel(int freq) {
        if (freq >= 2412 && freq <= 2484) return (freq - 2407) / 5;
        if (freq >= 5170 && freq <= 5825) return (freq - 5000) / 5;
        if (freq >= 5945 && freq <= 7125) return (freq - 5945) / 20 + 1;
        return -1;
    }

    private static String frequencyToBand(int freq) {
        if (freq < 2500) return "2.4 GHz";
        if (freq < 6000) return "5 GHz";
        return "6 GHz";
    }

    private static String channelWidthString(int width) {
        switch (width) {
            case ScanResult.CHANNEL_WIDTH_20MHZ: return "20 MHz";
            case ScanResult.CHANNEL_WIDTH_40MHZ: return "40 MHz";
            case ScanResult.CHANNEL_WIDTH_80MHZ: return "80 MHz";
            case ScanResult.CHANNEL_WIDTH_80PLUS_MHZ: return "80+80 MHz";
            case ScanResult.CHANNEL_WIDTH_160MHZ: return "160 MHz";
            case ScanResult.CHANNEL_WIDTH_320MHZ: return "320 MHz";
            default: return "unknown";
        }
    }

    @SuppressWarnings("deprecation")
    private static String keyMgmtToString(WifiConfiguration cfg) {
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA3_SAE)) return "WPA3";
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)) return "WPA2";
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) return "WPA";
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) return "WPA_EAP";
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) return "IEEE8021X";
        if (cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) return "OPEN";
        return "UNKNOWN";
    }

    @SuppressWarnings("deprecation")
    private static String configStatusToString(int status) {
        switch (status) {
            case WifiConfiguration.Status.CURRENT: return "connected";
            case WifiConfiguration.Status.ENABLED: return "enabled";
            case WifiConfiguration.Status.DISABLED: return "disabled";
            default: return "unknown";
        }
    }

    private static String intToIp(int addr) {
        return ((addr & 0xFF) + "." +
                ((addr >> 8) & 0xFF) + "." +
                ((addr >> 16) & 0xFF) + "." +
                ((addr >> 24) & 0xFF));
    }
}

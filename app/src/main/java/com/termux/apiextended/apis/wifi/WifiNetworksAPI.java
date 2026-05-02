package com.termux.apiextended.apis.wifi;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.util.PermissionManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * WiFi Networks API — Scan results, saved networks, signal monitoring.
 *
 * Methods:
 *   scan      — Scan visible networks (enhanced with security, channel, band info)
 *   saved     — List saved/configured networks (legacy only, API <29)
 *   info      — Detailed current connection info
 *   signals   — One-shot signal strength reading
 *
 * WifiConfiguration accessed via reflection since it was removed from SDK 30+.
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

    private String doScan(Context context, String id, String params) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        wm.startScan();

        List<ScanResult> results = wm.getScanResults();
        if (results == null || results.isEmpty()) {
            return CommandDispatcher.buildError(id, "NO_RESULTS",
                    "No networks found. Ensure location is enabled.");
        }

        Collections.sort(results, (a, b) -> b.level - a.level);

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
     * List saved networks. Uses reflection for WifiConfiguration (removed from SDK 30+).
     * Only works on pre-Android 10 devices.
     */
    @SuppressWarnings("deprecation")
    private String doSaved(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        try {
            Method getConfiguredNetworks = WifiManager.class.getMethod("getConfiguredNetworks");
            @SuppressWarnings("unchecked")
            java.util.List<?> configs = (java.util.List<?>) getConfiguredNetworks.invoke(wm);

            if (configs == null || configs.isEmpty()) {
                return CommandDispatcher.buildResponse(id, "{\"networks\":[],\"count\":0}");
            }

            Class<?> wcClass = Class.forName("android.net.wifi.WifiConfiguration");

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object cfg : configs) {
                if (!first) sb.append(",");
                first = false;

                String ssid = (String) wcClass.getField("SSID").get(cfg);
                if (ssid == null) ssid = "unknown";
                else ssid = ssid.replace("\"", "");

                int networkId = wcClass.getField("networkId").getInt(cfg);
                boolean hiddenSSID = wcClass.getField("hiddenSSID").getBoolean(cfg);
                int priority = wcClass.getField("priority").getInt(cfg);
                int status = wcClass.getField("status").getInt(cfg);

                String keyMgmt = keyMgmtToString(wcClass, cfg);
                String statusStr = configStatusToString(status);

                sb.append("{");
                sb.append("\"network_id\":").append(networkId).append(",");
                sb.append("\"ssid\":\"").append(PermissionManager.escapeJson(ssid)).append("\",");
                sb.append("\"security\":\"").append(keyMgmt).append("\",");
                sb.append("\"status\":\"").append(statusStr).append("\",");
                sb.append("\"hidden\":").append(hiddenSSID).append(",");
                sb.append("\"priority\":").append(priority);
                sb.append("}");
            }
            sb.append("]");

            return CommandDispatcher.buildResponse(id,
                "{\"networks\":" + sb.toString() + ","
              + "\"count\":" + configs.size() + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "SAVED_FAILED",
                    "Cannot read saved networks: " + e.getMessage()
                  + ". On Android 10+, saved networks are not accessible to non-system apps.");
        }
    }

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

    @SuppressWarnings("deprecation")
    private String doSignals(Context context, String id) {
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        int rssi = info.getRssi();
        int speed = info.getLinkSpeed();

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
            case 4: return "80+80 MHz"; // CHANNEL_WIDTH_80PLUS_MHZ (hidden API, value=4)
            case ScanResult.CHANNEL_WIDTH_160MHZ: return "160 MHz";
            case ScanResult.CHANNEL_WIDTH_320MHZ: return "320 MHz";
            default: return "unknown";
        }
    }

    /**
     * Determine key management type via reflection on WifiConfiguration.
     */
    private static String keyMgmtToString(Class<?> wcClass, Object cfg) {
        try {
            Class<?> kmClass = Class.forName("android.net.wifi.WifiConfiguration$KeyMgmt");
            Object allowedKM = wcClass.getField("allowedKeyManagement").get(cfg);

            // Check in order of specificity
            int wpa3sae = kmClass.getField("WPA3_SAE").getInt(null);
            int wpa2psk = kmClass.getField("WPA2_PSK").getInt(null);
            int wpaPsk = kmClass.getField("WPA_PSK").getInt(null);
            int wpaEap = kmClass.getField("WPA_EAP").getInt(null);
            int ieee8021x = kmClass.getField("IEEE8021X").getInt(null);
            int none = kmClass.getField("NONE").getInt(null);

            java.util.BitSet bs = (java.util.BitSet) allowedKM;
            if (bs.get(wpa3sae)) return "WPA3";
            if (bs.get(wpa2psk)) return "WPA2";
            if (bs.get(wpaPsk)) return "WPA";
            if (bs.get(wpaEap)) return "WPA_EAP";
            if (bs.get(ieee8021x)) return "IEEE8021X";
            if (bs.get(none)) return "OPEN";
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    private static String configStatusToString(int status) {
        // WifiConfiguration.Status constants: CURRENT=0, DISABLED=1, ENABLED=2
        switch (status) {
            case 0: return "connected";
            case 2: return "enabled";
            case 1: return "disabled";
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

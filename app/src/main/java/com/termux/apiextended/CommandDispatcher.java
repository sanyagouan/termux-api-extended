package com.termux.apiextended;

import android.content.Context;
import android.util.Log;

import com.termux.apiextended.apis.bluetooth.BluetoothAdapterAPI;
import com.termux.apiextended.apis.bluetooth.BluetoothPairingAPI;
import com.termux.apiextended.apis.bluetooth.BluetoothConnectionAPI;
import com.termux.apiextended.apis.bluetooth.BluetoothAudioAPI;
import com.termux.apiextended.apis.bluetooth.BluetoothTransferAPI;
import com.termux.apiextended.apis.bluetooth.BluetoothLEAPI;
import com.termux.apiextended.apis.wifi.WifiConnectAPI;
import com.termux.apiextended.apis.wifi.WifiNetworksAPI;
import com.termux.apiextended.apis.wifi.WifiHotspotAPI;
import com.termux.apiextended.apis.wifi.WifiDirectAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * Dispatches incoming JSON commands to the appropriate API module.
 * Each module handles a domain (wifi, bt, ble, hotspot, wifidirect).
 *
 * Protocol:
 *   Input:  {"id":"req_001","method":"bt.scan","params":{...}}
 *   Output: {"id":"req_001","status":"ok|error|stream","data":{...},"timestamp":...}
 */
public final class CommandDispatcher {

    private static final String TAG = "CommandDispatcher";
    private final Context context;
    private final Map<String, IApiModule> modules;

    public CommandDispatcher(Context context) {
        this.context = context.getApplicationContext();
        this.modules = new HashMap<>();
        registerModules();
    }

    private void registerModules() {
        // WiFi modules
        modules.put("wifi", new WifiConnectAPI());
        modules.put("wifi.networks", new WifiNetworksAPI());
        modules.put("hotspot", new WifiHotspotAPI());
        modules.put("wifidirect", new WifiDirectAPI());

        // Bluetooth modules
        modules.put("bt", new BluetoothAdapterAPI());
        modules.put("bt.pair", new BluetoothPairingAPI());
        modules.put("bt.connect", new BluetoothConnectionAPI());
        modules.put("bt.audio", new BluetoothAudioAPI());
        modules.put("bt.transfer", new BluetoothTransferAPI());
        modules.put("ble", new BluetoothLEAPI());
    }

    /**
     * Dispatch a raw JSON command string.
     *
     * @param jsonCommand Raw JSON input
     * @return JSON response string
     */
    public String dispatch(String jsonCommand) {
        try {
            // Minimal JSON parsing without external deps
            String id = extractString(jsonCommand, "id");
            String method = extractString(jsonCommand, "method");
            String params = extractObject(jsonCommand, "params");

            if (method == null || method.isEmpty()) {
                return buildError(id, "INVALID_REQUEST", "Missing 'method' field");
            }

            Log.d(TAG, "Dispatching: method=" + method + " id=" + id);

            // Route to the correct module based on method prefix
            String moduleKey = resolveModuleKey(method);
            IApiModule module = modules.get(moduleKey);

            if (module == null) {
                return buildError(id, "UNKNOWN_METHOD", "No handler for method: " + method);
            }

            String subMethod = method.startsWith(moduleKey + ".")
                    ? method.substring(moduleKey.length() + 1)
                    : method;

            return module.execute(context, subMethod, params);

        } catch (Exception e) {
            Log.e(TAG, "Dispatch error", e);
            return buildError("unknown", "INTERNAL_ERROR", e.getMessage());
        }
    }

    /**
     * Resolve which module handles the given method.
     * Uses longest-prefix matching so "bt.pair.list" goes to bt.pair module.
     */
    private String resolveModuleKey(String method) {
        String bestMatch = null;
        for (String key : modules.keySet()) {
            if (method.equals(key) || method.startsWith(key + ".")) {
                if (bestMatch == null || key.length() > bestMatch.length()) {
                    bestMatch = key;
                }
            }
        }
        return bestMatch;
    }

    // --- Minimal JSON helpers (no Gson dependency) ---

    /**
     * Extract a string value from a JSON object by key.
     */
    static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try with space after colon
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
            if (start < 0) return null;
        }
        start += search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < json.length()) {
                end += 2; // skip escaped char
            } else if (c == '"') {
                break;
            } else {
                end++;
            }
        }
        String value = json.substring(start, end);
        return unescapeJson(value);
    }

    /**
     * Extract a JSON object by key (returns raw substring).
     */
    static String extractObject(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return "{}";
        start += search.length();
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "{}";

        if (json.charAt(start) != '{') return "{}";

        int depth = 0;
        int end = start;
        boolean inString = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && (end == 0 || json.charAt(end - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return json.substring(start, end + 1);
                    }
                }
            }
            end++;
        }
        return json.substring(start, end);
    }

    /**
     * Extract an integer value from JSON by key.
     */
    static int extractInt(String json, String key, int defaultValue) {
        String s = extractString(json, key);
        if (s == null) {
            // Try numeric format
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) return defaultValue;
            start += search.length();
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start >= json.length()) return defaultValue;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extract a boolean value from JSON by key.
     */
    static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String s = extractString(json, key);
        if (s == null) {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) return defaultValue;
            start += search.length();
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (json.substring(start).startsWith("true")) return true;
            if (json.substring(start).startsWith("false")) return false;
            return defaultValue;
        }
        return Boolean.parseBoolean(s);
    }

    static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // --- Response builders ---

    public static String buildError(String id, String code, String message) {
        return "{\"id\":\"" + escape(id) + "\","
             + "\"status\":\"error\","
             + "\"error\":{\"code\":\"" + escape(code) + "\","
             + "\"message\":\"" + escape(message) + "\"},"
             + "\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    public static String buildResponse(String id, String data) {
        return "{\"id\":\"" + escape(id) + "\","
             + "\"status\":\"ok\","
             + "\"data\":" + data + ","
             + "\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    public static String buildStream(String id, String data, int seq) {
        return "{\"id\":\"" + escape(id) + "\","
             + "\"status\":\"stream\","
             + "\"data\":" + data + ","
             + "\"seq\":" + seq + "}";
    }

    public static String buildStreamDone(String id, String data) {
        return "{\"id\":\"" + escape(id) + "\","
             + "\"status\":\"done\","
             + "\"data\":" + data + ","
             + "\"timestamp\":" + System.currentTimeMillis() + "}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

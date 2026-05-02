package com.termux.apiextended.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized permission management.
 * Checks and reports missing permissions required by API modules.
 */
public final class PermissionManager {

    private PermissionManager() {} // Utility class

    // --- WiFi permissions ---
    public static final String[] WIFI_PERMISSIONS = {
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_NETWORK_STATE,
    };

    // --- Bluetooth Classic permissions ---
    public static final String[] BT_PERMISSIONS;
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BT_PERMISSIONS = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            };
        } else {
            BT_PERMISSIONS = new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            };
        }
    }

    // --- Hotspot permissions ---
    public static final String[] HOTSPOT_PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CHANGE_NETWORK_STATE,
    };

    /**
     * Check if all permissions in the array are granted.
     *
     * @param context     Android context
     * @param permissions Permissions to check
     * @return List of missing permission names (empty if all granted)
     */
    public static List<String> checkPermissions(Context context, String[] permissions) {
        List<String> missing = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        return missing;
    }

    /**
     * Build a JSON-formatted error response for missing permissions.
     *
     * @param requestId   Request ID for correlation
     * @param permissions List of missing permissions
     * @return JSON error string
     */
    public static String buildPermissionError(String requestId, List<String> permissions) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(escapeJson(requestId)).append("\",");
        sb.append("\"status\":\"error\",");
        sb.append("\"error\":{\"code\":\"PERMISSION_DENIED\",");
        sb.append("\"message\":\"Missing permissions\",");
        sb.append("\"permissions\":[");
        for (int i = 0; i < permissions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(permissions.get(i))).append("\"");
        }
        sb.append("]},");
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append("}");
        return sb.toString();
    }

    /**
     * Escape a string for safe JSON embedding.
     */
    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

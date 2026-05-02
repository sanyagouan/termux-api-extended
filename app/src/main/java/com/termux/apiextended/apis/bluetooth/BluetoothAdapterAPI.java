package com.termux.apiextended.apis.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.util.PermissionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bluetooth Adapter API — Enable/disable, scan, status, discoverable mode.
 *
 * Methods:
 *   enable       — Turn Bluetooth on
 *   disable      — Turn Bluetooth off
 *   status       — Adapter info (name, address, state)
 *   discoverable — Set discoverable mode with timeout
 *   scan         — Classic/LE scan with RSSI, type, name
 */
public class BluetoothAdapterAPI implements IApiModule {

    private static final String TAG = "BluetoothAdapterAPI";
    private static final int REQUEST_ENABLE_BT = 1002;
    private static final int REQUEST_DISCOVERABLE = 1003;

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.BT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "bt_" + System.currentTimeMillis();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return CommandDispatcher.buildError(requestId, "NO_BLUETOOTH",
                    "Device does not support Bluetooth");
        }

        switch (method) {
            case "enable":
                return doEnable(context, requestId, adapter);
            case "disable":
                return doDisable(requestId, adapter);
            case "status":
                return doStatus(context, requestId, adapter);
            case "discoverable":
                return doDiscoverable(context, requestId, params, adapter);
            case "scan":
                return doScan(context, requestId, params, adapter);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown bt method: " + method);
        }
    }

    /**
     * Enable Bluetooth adapter.
     */
    private String doEnable(Context context, String id, BluetoothAdapter adapter) {
        if (adapter.isEnabled()) {
            return CommandDispatcher.buildResponse(id,
                "{\"enabled\":true,\"message\":\"Bluetooth already enabled\"}");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: BLUETOOTH_CONNECT permission allows programmatic enable
            try {
                adapter.enable();
                return CommandDispatcher.buildResponse(id,
                    "{\"enabled\":true,\"message\":\"Bluetooth enable requested. "
                  + "Note: some Android 12+ devices ignore programmatic enable. "
                  + "Enable manually from Settings if this doesn't work.\"}");
            } catch (SecurityException e) {
                return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
            }
        } else {
            // Legacy: use intent
            try {
                adapter.enable();
                return CommandDispatcher.buildResponse(id,
                    "{\"enabled\":true,\"message\":\"Bluetooth enable requested\"}");
            } catch (SecurityException e) {
                return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
            }
        }
    }

    /**
     * Disable Bluetooth adapter.
     */
    private String doDisable(String id, BluetoothAdapter adapter) {
        if (!adapter.isEnabled()) {
            return CommandDispatcher.buildResponse(id,
                "{\"enabled\":false,\"message\":\"Bluetooth already disabled\"}");
        }

        try {
            adapter.disable();
            return CommandDispatcher.buildResponse(id,
                "{\"enabled\":false,\"message\":\"Bluetooth disable requested\"}");
        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        }
    }

    /**
     * Get Bluetooth adapter status and info.
     */
    private String doStatus(Context context, String id, BluetoothAdapter adapter) {
        String name;
        String address;
        int state;

        try {
            name = adapter.getName();
            address = adapter.getAddress();
            state = adapter.getState();
        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION",
                    "Cannot read adapter info: " + e.getMessage());
        }

        String stateStr;
        switch (state) {
            case BluetoothAdapter.STATE_OFF: stateStr = "off"; break;
            case BluetoothAdapter.STATE_TURNING_ON: stateStr = "turning_on"; break;
            case BluetoothAdapter.STATE_ON: stateStr = "on"; break;
            case BluetoothAdapter.STATE_TURNING_OFF: stateStr = "turning_off"; break;
            default: stateStr = "unknown"; break;
        }

        // Bluetooth LE support
        boolean bleSupported = adapter.isMultipleAdvertisementSupported();
        boolean leSupported = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE);

        // Scan mode
        int scanMode;
        try {
            scanMode = adapter.getScanMode();
        } catch (SecurityException e) {
            scanMode = -1;
        }

        String scanModeStr;
        switch (scanMode) {
            case BluetoothAdapter.SCAN_MODE_NONE: scanModeStr = "none"; break;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE: scanModeStr = "connectable"; break;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE: scanModeStr = "discoverable"; break;
            default: scanModeStr = "unknown"; break;
        }

        return CommandDispatcher.buildResponse(id,
            "{\"name\":\"" + PermissionManager.escapeJson(name != null ? name : "unknown") + "\","
          + "\"address\":\"" + PermissionManager.escapeJson(address != null ? address : "unknown") + "\","
          + "\"state\":\"" + stateStr + "\","
          + "\"scan_mode\":\"" + scanModeStr + "\","
          + "\"enabled\":" + adapter.isEnabled() + ","
          + "\"discovering\":" + adapter.isDiscovering() + ","
          + "\"le_supported\":" + leSupported + ","
          + "\"multiple_advertisement_supported\":" + bleSupported + ","
          + "\"bonded_devices\":" + getBondedDeviceCount(adapter) + "}");
    }

    /**
     * Set discoverable mode.
     */
    private String doDiscoverable(Context context, String id, String params,
                                  BluetoothAdapter adapter) {
        int duration = CommandDispatcher.extractInt(params, "duration", 120);

        if (!adapter.isEnabled()) {
            return CommandDispatcher.buildError(id, "BT_DISABLED",
                    "Bluetooth must be enabled first");
        }

        try {
            Intent discoverableIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(
                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration);
            discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(discoverableIntent);

            return CommandDispatcher.buildResponse(id,
                "{\"duration_seconds\":" + duration + ","
              + "\"message\":\"Discoverable mode requested for " + duration + " seconds. "
              + "Note: This opens a system dialog on the device.\"}");
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "DISCOVERABLE_FAILED", e.getMessage());
        }
    }

    /**
     * Scan for Bluetooth devices (both Classic and LE).
     * Uses BLE scanner on Android 5+ for better results.
     * Falls back to classic startDiscovery for older devices.
     */
    private String doScan(Context context, String id, String params,
                          BluetoothAdapter adapter) {
        int duration = CommandDispatcher.extractInt(params, "duration", 10);
        String uuidFilter = CommandDispatcher.extractString(params, "uuid_filter");
        boolean leOnly = CommandDispatcher.extractBoolean(params, "le_only", false);

        if (!adapter.isEnabled()) {
            return CommandDispatcher.buildError(id, "BT_DISABLED",
                    "Bluetooth must be enabled first");
        }

        // Use BLE scanner (available on Android 5+, works for both LE and some Classic)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return scanBLE(context, id, adapter, duration, uuidFilter);
        }

        // Fallback: classic discovery
        return scanClassic(context, id, adapter, duration);
    }

    /**
     * BLE-based scan (Android 5+).
     * Uses a latch to collect results synchronously within the timeout.
     */
    private String scanBLE(Context context, String id, BluetoothAdapter adapter,
                           int duration, String uuidFilter) {
        AtomicReference<List<ScanResult>> resultsRef = new AtomicReference<>(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return CommandDispatcher.buildError(id, "SCANNER_UNAVAILABLE",
                    "BLE scanner not available");
        }

        // Build scan filters
        List<ScanFilter> filters = new ArrayList<>();
        if (uuidFilter != null && !uuidFilter.isEmpty()) {
            try {
                ScanFilter.Builder filterBuilder = new ScanFilter.Builder();
                filterBuilder.setServiceUuid(ParcelUuid.fromString(uuidFilter));
                filters.add(filterBuilder.build());
            } catch (IllegalArgumentException e) {
                return CommandDispatcher.buildError(id, "INVALID_UUID",
                        "Invalid UUID filter: " + uuidFilter);
            }
        }

        // Scan settings: low latency for quick results
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                resultsRef.get().add(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                resultsRef.get().addAll(results);
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
                latch.countDown();
            }
        };

        try {
            scanner.startScan(filters, settings, callback);

            // Wait for scan duration
            boolean completed = latch.await(duration, TimeUnit.SECONDS);

            scanner.stopScan(callback);

            List<ScanResult> results = resultsRef.get();
            // Deduplicate by address (keep strongest RSSI)
            java.util.Map<String, ScanResult> deduped = new java.util.LinkedHashMap<>();
            for (ScanResult r : results) {
                ScanResult existing = deduped.get(r.getDevice().getAddress());
                if (existing == null || r.getRssi() > existing.getRssi()) {
                    deduped.put(r.getDevice().getAddress(), r);
                }
            }

            // Build JSON
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ScanResult r : deduped.values()) {
                if (!first) sb.append(",");
                first = false;

                BluetoothDevice device = r.getDevice();
                String name = null;
                try { name = device.getName(); } catch (SecurityException ignored) {}

                sb.append("{");
                sb.append("\"name\":\"").append(PermissionManager.escapeJson(
                        name != null ? name : "unknown")).append("\",");
                sb.append("\"address\":\"").append(device.getAddress()).append("\",");
                sb.append("\"rssi\":").append(r.getRssi()).append(",");

                // Signal quality
                int quality = Math.max(0, Math.min(100, r.getRssi() + 100));
                sb.append("\"quality_percent\":").append(quality).append(",");

                // Determine type
                sb.append("\"type\":\"");
                if (r.getDevice().getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                    sb.append("BLE");
                } else if (r.getDevice().getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                    sb.append("Classic");
                } else if (r.getDevice().getType() == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    sb.append("Dual");
                } else {
                    sb.append("Unknown");
                }
                sb.append("\",");

                // Service UUIDs from scan record
                sb.append("\"services\":[");
                if (r.getScanRecord() != null) {
                    List<ParcelUuid> uuids = r.getScanRecord().getServiceUuids();
                    if (uuids != null) {
                        boolean uFirst = true;
                        for (ParcelUuid uuid : uuids) {
                            if (!uFirst) sb.append(",");
                            uFirst = true;
                            sb.append("\"").append(uuid.toString()).append("\"");
                        }
                    }
                }
                sb.append("],");

                // TX power level
                if (r.getScanRecord() != null && r.getScanRecord().getTxPowerLevel() != Integer.MIN_VALUE) {
                    sb.append("\"tx_power_level\":").append(r.getScanRecord().getTxPowerLevel()).append(",");
                }

                sb.append("\"timestamp_nanos\":").append(r.getTimestampNanos());
                sb.append("}");
            }
            sb.append("]");

            return CommandDispatcher.buildResponse(id,
                "{\"devices\":" + sb.toString() + ","
              + "\"count\":" + deduped.size() + ","
              + "\"scan_duration_seconds\":" + duration + ","
              + "\"scan_type\":\"BLE\"}");

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandDispatcher.buildError(id, "SCAN_INTERRUPTED", "Scan was interrupted");
        }
    }

    /**
     * Classic Bluetooth discovery (pre-Lollipop fallback).
     */
    private String scanClassic(Context context, String id, BluetoothAdapter adapter, int duration) {
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }

        adapter.startDiscovery();

        // Wait for discovery
        try {
            Thread.sleep(duration * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        adapter.cancelDiscovery();

        // Get paired + discovered devices
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (!first) sb.append(",");
                first = false;
                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = "unknown"; }
                sb.append("{")
                  .append("\"name\":\"").append(PermissionManager.escapeJson(name)).append("\",")
                  .append("\"address\":\"").append(device.getAddress()).append("\",")
                  .append("\"bonded\":true,")
                  .append("\"type\":\"Classic\"")
                  .append("}");
            }
        } catch (SecurityException ignored) {}
        sb.append("]");

        return CommandDispatcher.buildResponse(id,
            "{\"devices\":" + sb.toString() + ","
          + "\"count\":0,"
          + "\"scan_duration_seconds\":" + duration + ","
          + "\"scan_type\":\"Classic\","
          + "\"note\":\"Classic scan only returns bonded devices. "
          + "Use BLE scan (Android 5+) for full discovery.\"}");
    }

    private int getBondedDeviceCount(BluetoothAdapter adapter) {
        try {
            return adapter.getBondedDevices().size();
        } catch (SecurityException e) {
            return -1;
        }
    }
}

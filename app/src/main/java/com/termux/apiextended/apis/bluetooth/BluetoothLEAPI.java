package com.termux.apiextended.apis.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.util.PermissionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bluetooth LE API — Full BLE operations.
 *
 * Methods:
 *   scan           — Scan BLE devices (with filters, RSSI, advertising data)
 *   scan.filter    — Scan by service UUID
 *   connect        — Connect to BLE device
 *   disconnect     — Disconnect from BLE device
 *   services       — Discover services of connected device
 *   characteristics — List characteristics of a service
 *   read           — Read characteristic value
 *   write          — Write value to characteristic
 *   notify         — Subscribe to characteristic notifications
 *   rssi           — Read RSSI
 *   mtu            — Request MTU size
 */
public class BluetoothLEAPI implements IApiModule {

    private static final String TAG = "BluetoothLEAPI";

    // GATT callback result holder
    private static class GattResult {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String data;
        volatile boolean success;
        volatile int status;
    }

    // Active GATT connections
    private final Map<String, BluetoothGatt> activeConnections = new HashMap<>();

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.BT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "ble_" + System.currentTimeMillis();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return CommandDispatcher.buildError(requestId, "NO_BLUETOOTH",
                    "Device does not support Bluetooth");
        }

        switch (method) {
            case "scan":
                return doScan(requestId, params, adapter);
            case "scan.filter":
                return doScanFilter(requestId, params, adapter);
            case "connect":
                return doConnect(context, requestId, params, adapter);
            case "disconnect":
                return doDisconnect(requestId, params);
            case "services":
                return doServices(context, requestId, params);
            case "characteristics":
                return doCharacteristics(requestId, params);
            case "read":
                return doRead(context, requestId, params);
            case "write":
                return doWrite(context, requestId, params);
            case "notify":
                return doNotify(context, requestId, params);
            case "rssi":
                return doRssi(context, requestId, params);
            case "mtu":
                return doMtu(context, requestId, params);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown ble method: " + method);
        }
    }

    /**
     * Scan for BLE devices.
     */
    private String doScan(String id, String params, BluetoothAdapter adapter) {
        int duration = CommandDispatcher.extractInt(params, "duration", 10);

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return CommandDispatcher.buildError(id, "SCANNER_UNAVAILABLE",
                    "BLE scanner not available. Is Bluetooth enabled?");
        }

        AtomicReference<List<ScanResult>> resultsRef = new AtomicReference<>(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

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
                latch.countDown();
            }
        };

        try {
            scanner.startScan(null, settings, callback);
            latch.await(duration, TimeUnit.SECONDS);
            scanner.stopScan(callback);

            List<ScanResult> results = resultsRef.get();

            // Deduplicate by address
            Map<String, ScanResult> deduped = new java.util.LinkedHashMap<>();
            for (ScanResult r : results) {
                ScanResult existing = deduped.get(r.getDevice().getAddress());
                if (existing == null || r.getRssi() > existing.getRssi()) {
                    deduped.put(r.getDevice().getAddress(), r);
                }
            }

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ScanResult r : deduped.values()) {
                if (!first) sb.append(",");
                first = false;

                BluetoothDevice device = r.getDevice();
                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = null; }

                sb.append("{");
                sb.append("\"name\":\"").append(PermissionManager.escapeJson(
                        name != null ? name : "unknown")).append("\",");
                sb.append("\"address\":\"").append(device.getAddress()).append("\",");
                sb.append("\"rssi\":").append(r.getRssi()).append(",");
                sb.append("\"quality_percent\":").append(Math.max(0, Math.min(100, r.getRssi() + 100))).append(",");

                // Advertising data
                if (r.getScanRecord() != null) {
                    sb.append("\"tx_power\":" + r.getScanRecord().getTxPowerLevel() + ",");

                    // Service UUIDs
                    List<ParcelUuid> uuids = r.getScanRecord().getServiceUuids();
                    if (uuids != null && !uuids.isEmpty()) {
                        sb.append("\"services\":[");
                        boolean uFirst = true;
                        for (ParcelUuid uuid : uuids) {
                            if (!uFirst) sb.append(",");
                            uFirst = false;
                            sb.append("\"").append(uuid.toString()).append("\"");
                        }
                        sb.append("],");
                    }

                    // Manufacturer data
                    android.util.SparseArray<byte[]> mfrData = r.getScanRecord().getManufacturerSpecificData();
                    if (mfrData != null && mfrData.size() > 0) {
                        sb.append("\"manufacturer_data\":{");
                        boolean mFirst = true;
                        for (int i = 0; i < mfrData.size(); i++) {
                            if (!mFirst) sb.append(",");
                            mFirst = false;
                            sb.append("\"").append(mfrData.keyAt(i)).append("\":\"");
                            sb.append(bytesToHex(mfrData.valueAt(i))).append("\"");
                        }
                        sb.append("},");
                    }
                }

                sb.append("\"connectable\":true");
                sb.append("}");
            }
            sb.append("]");

            return CommandDispatcher.buildResponse(id,
                "{\"devices\":" + sb.toString() + ","
              + "\"count\":" + deduped.size() + "}");

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandDispatcher.buildError(id, "SCAN_INTERRUPTED", "Scan interrupted");
        }
    }

    /**
     * Scan with UUID filter.
     */
    private String doScanFilter(String id, String params, BluetoothAdapter adapter) {
        String uuid = CommandDispatcher.extractString(params, "uuid");
        if (uuid == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'uuid' parameter");
        }

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return CommandDispatcher.buildError(id, "SCANNER_UNAVAILABLE", "BLE scanner not available");
        }

        AtomicReference<List<ScanResult>> resultsRef = new AtomicReference<>(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        int duration = CommandDispatcher.extractInt(params, "duration", 10);

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(uuid))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                resultsRef.get().add(result);
            }
            @Override
            public void onScanFailed(int errorCode) { latch.countDown(); }
        };

        try {
            scanner.startScan(List.of(filter), settings, callback);
            latch.await(duration, TimeUnit.SECONDS);
            scanner.stopScan(callback);

            List<ScanResult> results = resultsRef.get();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (ScanResult r : results) {
                if (!first) sb.append(",");
                first = false;
                String name;
                try { name = r.getDevice().getName(); } catch (SecurityException e) { name = "unknown"; }
                sb.append("{\"name\":\"").append(PermissionManager.escapeJson(name))
                  .append("\",\"address\":\"").append(r.getDevice().getAddress())
                  .append("\",\"rssi\":").append(r.getRssi()).append("}");
            }
            sb.append("]");

            return CommandDispatcher.buildResponse(id,
                "{\"devices\":" + sb.toString() + ","
              + "\"filter_uuid\":\"" + uuid + "\","
              + "\"count\":" + results.size() + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "FILTERED_SCAN_FAILED", e.getMessage());
        }
    }

    /**
     * Connect to a BLE device.
     */
    private String doConnect(Context context, String id, String params,
                             BluetoothAdapter adapter) {
        String address = CommandDispatcher.extractString(params, "address");
        if (address == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'address'");
        }

        BluetoothDevice device = adapter.getRemoteDevice(address);
        GattResult result = new GattResult();

        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gatt = device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // Discover services
                        g.discoverServices();
                    } else {
                        result.success = false;
                        result.status = status;
                        result.latch.countDown();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    result.success = (status == BluetoothGatt.GATT_SUCCESS);
                    result.status = status;
                    result.data = servicesToJson(g.getServices());
                    result.latch.countDown();
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices();
                    } else {
                        result.success = false;
                        result.status = status;
                        result.latch.countDown();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    result.success = (status == BluetoothGatt.GATT_SUCCESS);
                    result.status = status;
                    result.data = servicesToJson(g.getServices());
                    result.latch.countDown();
                }
            });
        }

        activeConnections.put(address, gatt);

        try {
            boolean completed = result.latch.await(15, TimeUnit.SECONDS);
            if (!completed) {
                gatt.close();
                activeConnections.remove(address);
                return CommandDispatcher.buildError(id, "CONNECT_TIMEOUT",
                        "BLE connection timed out after 15 seconds");
            }

            if (result.success) {
                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"connected\":true,"
                  + "\"services\":" + result.data + "}");
            } else {
                gatt.close();
                activeConnections.remove(address);
                return CommandDispatcher.buildError(id, "CONNECT_FAILED",
                        "BLE connection failed with GATT status: " + result.status);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gatt.close();
            activeConnections.remove(address);
            return CommandDispatcher.buildError(id, "CONNECT_INTERRUPTED", "Connection interrupted");
        }
    }

    /**
     * Disconnect from a BLE device.
     */
    private String doDisconnect(String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        if (address == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'address'");
        }

        BluetoothGatt gatt = activeConnections.remove(address);
        if (gatt == null) {
            return CommandDispatcher.buildResponse(id,
                "{\"address\":\"" + address + "\","
              + "\"disconnected\":true,"
              + "\"note\":\"No active connection found\"}");
        }

        gatt.disconnect();
        gatt.close();

        return CommandDispatcher.buildResponse(id,
            "{\"address\":\"" + address + "\","
          + "\"disconnected\":true}");
    }

    /**
     * List services of a connected device.
     */
    private String doServices(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        String servicesJson = servicesToJson(gatt.getServices());
        return CommandDispatcher.buildResponse(id,
            "{\"address\":\"" + address + "\","
          + "\"services\":" + servicesJson + "}");
    }

    /**
     * List characteristics of a specific service.
     */
    private String doCharacteristics(String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String serviceUuid = CommandDispatcher.extractString(params, "service_uuid");
        if (serviceUuid == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'service_uuid'");
        }

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            return CommandDispatcher.buildError(id, "SERVICE_NOT_FOUND",
                    "Service " + serviceUuid + " not found");
        }

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"uuid\":\"").append(chr.getUuid()).append("\",");
            sb.append("\"properties\":{");
            sb.append("\"read\":").append(chr.getProperties() & 0x02).append(",");
            sb.append("\"write\":").append((chr.getProperties() & 0x08) != 0).append(",");
            sb.append("\"write_no_response\":").append((chr.getProperties() & 0x04) != 0).append(",");
            sb.append("\"notify\":").append((chr.getProperties() & 0x10) != 0).append(",");
            sb.append("\"indicate\":").append((chr.getProperties() & 0x20) != 0);
            sb.append("},");

            // Format value
            sb.append("\"value\":\"");
            if (chr.getValue() != null) {
                sb.append(bytesToHex(chr.getValue()));
            }
            sb.append("\"");

            sb.append("}");
        }
        sb.append("]");

        return CommandDispatcher.buildResponse(id,
            "{\"service_uuid\":\"" + serviceUuid + "\","
          + "\"characteristics\":" + sb.toString() + "}");
    }

    /**
     * Read a characteristic value.
     */
    private String doRead(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String serviceUuid = CommandDispatcher.extractString(params, "service_uuid");
        String charUuid = CommandDispatcher.extractString(params, "characteristic_uuid");

        if (serviceUuid == null || charUuid == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'service_uuid' or 'characteristic_uuid'");
        }

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            return CommandDispatcher.buildError(id, "SERVICE_NOT_FOUND", "Service not found");
        }

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) {
            return CommandDispatcher.buildError(id, "CHARACTERISTIC_NOT_FOUND",
                    "Characteristic not found");
        }

        GattResult result = new GattResult();

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                result.success = (status == BluetoothGatt.GATT_SUCCESS);
                result.status = status;
                if (c.getValue() != null) {
                    result.data = bytesToHex(c.getValue());
                }
                result.latch.countDown();
            }
        };

        // We need to use the existing gatt with a new callback.
        // Since BluetoothGatt only accepts one callback, we re-connect for read operations.
        BluetoothDevice device = gatt.getDevice();
        gatt.close();
        activeConnections.remove(address);

        BluetoothGatt newGatt = device.connectGatt(context, false, callback);
        activeConnections.put(address, newGatt);

        // Wait for connection, then read
        try {
            Thread.sleep(2000); // Wait for connection + service discovery
            newGatt.readCharacteristic(characteristic);
            boolean completed = result.latch.await(10, TimeUnit.SECONDS);

            if (!completed) {
                return CommandDispatcher.buildError(id, "READ_TIMEOUT", "Read timed out");
            }

            if (result.success) {
                return CommandDispatcher.buildResponse(id,
                    "{\"uuid\":\"" + charUuid + "\","
                  + "\"value\":\"" + (result.data != null ? result.data : "") + "\","
                  + "\"value_bytes\":" + (result.data != null ? hexToBytesArray(result.data) : "[]") + "}");
            } else {
                return CommandDispatcher.buildError(id, "READ_FAILED",
                        "GATT read failed with status: " + result.status);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandDispatcher.buildError(id, "READ_INTERRUPTED", "Read interrupted");
        }
    }

    /**
     * Write a value to a characteristic.
     */
    private String doWrite(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String serviceUuid = CommandDispatcher.extractString(params, "service_uuid");
        String charUuid = CommandDispatcher.extractString(params, "characteristic_uuid");
        String valueHex = CommandDispatcher.extractString(params, "value");
        boolean withResponse = CommandDispatcher.extractBoolean(params, "with_response", true);

        if (serviceUuid == null || charUuid == null || valueHex == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing required parameters: service_uuid, characteristic_uuid, value");
        }

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            return CommandDispatcher.buildError(id, "SERVICE_NOT_FOUND", "Service not found");
        }

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) {
            return CommandDispatcher.buildError(id, "CHARACTERISTIC_NOT_FOUND",
                    "Characteristic not found");
        }

        byte[] bytes = hexToBytes(valueHex);
        characteristic.setValue(bytes);

        GattResult result = new GattResult();

        BluetoothDevice device = gatt.getDevice();
        gatt.close();
        activeConnections.remove(address);

        BluetoothGatt newGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                result.success = (status == BluetoothGatt.GATT_SUCCESS);
                result.status = status;
                result.latch.countDown();
            }
        });
        activeConnections.put(address, newGatt);

        try {
            Thread.sleep(2000);
            boolean written;
            if (withResponse) {
                written = newGatt.writeCharacteristic(characteristic);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    written = newGatt.writeCharacteristic(characteristic, bytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                } else {
                    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    written = newGatt.writeCharacteristic(characteristic);
                }
            }

            if (!written) {
                return CommandDispatcher.buildError(id, "WRITE_FAILED", "writeCharacteristic returned false");
            }

            boolean completed = result.latch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                return CommandDispatcher.buildResponse(id,
                    "{\"written\":true,\"confirmed\":false,\"note\":\"Write sent, no confirmation received\"}");
            }

            return CommandDispatcher.buildResponse(id,
                "{\"written\":true,\"confirmed\":" + result.success + ","
              + "\"status\":" + result.status + "}");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandDispatcher.buildError(id, "WRITE_INTERRUPTED", "Write interrupted");
        }
    }

    /**
     * Subscribe to characteristic notifications.
     */
    private String doNotify(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String serviceUuid = CommandDispatcher.extractString(params, "service_uuid");
        String charUuid = CommandDispatcher.extractString(params, "characteristic_uuid");
        boolean enable = CommandDispatcher.extractBoolean(params, "enable", true);

        if (serviceUuid == null || charUuid == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'service_uuid' or 'characteristic_uuid'");
        }

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUuid));
        if (service == null) {
            return CommandDispatcher.buildError(id, "SERVICE_NOT_FOUND", "Service not found");
        }

        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(UUID.fromString(charUuid));
        if (characteristic == null) {
            return CommandDispatcher.buildError(id, "CHARACTERISTIC_NOT_FOUND",
                    "Characteristic not found");
        }

        boolean setNotify = gatt.setCharacteristicNotification(characteristic, enable);

        if (setNotify) {
            // Enable the CCCD (Client Characteristic Configuration Descriptor)
            BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (cccd != null) {
                byte[] value;
                if (enable) {
                    // Enable notifications (0x01) or indications (0x02)
                    boolean indicate = (characteristic.getProperties() & 0x20) != 0;
                    value = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                gatt.writeDescriptor(cccd, value);
            }
        }

        return CommandDispatcher.buildResponse(id,
            "{\"characteristic_uuid\":\"" + charUuid + "\","
          + "\"notify_enabled\":" + setNotify + ","
          + "\"message\":\"Notification " + (enable ? "enabled" : "disabled")
          + ". Use streaming protocol to receive updates.\"}");
    }

    /**
     * Read RSSI of connected device.
     */
    private String doRssi(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        GattResult result = new GattResult();

        BluetoothDevice device = gatt.getDevice();
        gatt.close();
        activeConnections.remove(address);

        BluetoothGatt newGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
                result.success = (status == BluetoothGatt.GATT_SUCCESS);
                result.data = String.valueOf(rssi);
                result.status = status;
                result.latch.countDown();
            }
        });
        activeConnections.put(address, newGatt);

        try {
            Thread.sleep(2000);
            newGatt.readRemoteRssi();
            boolean completed = result.latch.await(10, TimeUnit.SECONDS);

            if (!completed || !result.success) {
                return CommandDispatcher.buildError(id, "RSSI_READ_FAILED",
                        "Failed to read RSSI" + (!completed ? " (timeout)" : " (status: " + result.status + ")"));
            }

            return CommandDispatcher.buildResponse(id,
                "{\"rssi\":" + result.data + ","
              + "\"quality_percent\":" + Math.max(0, Math.min(100,
                    Integer.parseInt(result.data) + 100)) + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "RSSI_ERROR", e.getMessage());
        }
    }

    /**
     * Request MTU size.
     */
    private String doMtu(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        int mtu = CommandDispatcher.extractInt(params, "mtu", 517);

        BluetoothGatt gatt = getActiveGatt(id, address);
        if (gatt == null) return null;

        GattResult result = new GattResult();

        BluetoothDevice device = gatt.getDevice();
        gatt.close();
        activeConnections.remove(address);

        BluetoothGatt newGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                result.success = (status == BluetoothGatt.GATT_SUCCESS);
                result.data = String.valueOf(mtu);
                result.status = status;
                result.latch.countDown();
            }
        });
        activeConnections.put(address, newGatt);

        try {
            Thread.sleep(2000);
            newGatt.requestMtu(mtu);
            boolean completed = result.latch.await(10, TimeUnit.SECONDS);

            if (!completed || !result.success) {
                return CommandDispatcher.buildError(id, "MTU_FAILED",
                        "Failed to negotiate MTU");
            }

            return CommandDispatcher.buildResponse(id,
                "{\"mtu\":" + result.data + ","
              + "\"requested\":" + mtu + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "MTU_ERROR", e.getMessage());
        }
    }

    // --- Helpers ---

    private BluetoothGatt getActiveGatt(String id, String address) {
        if (address == null) {
            CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'address'");
            return null;
        }
        BluetoothGatt gatt = activeConnections.get(address);
        if (gatt == null) {
            CommandDispatcher.buildError(id, "NOT_CONNECTED",
                    "No active BLE connection to " + address);
            return null;
        }
        return gatt;
    }

    private static String servicesToJson(List<BluetoothGattService> services) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (BluetoothGattService service : services) {
            if (!first) sb.append(",");
            first = false;

            sb.append("{");
            sb.append("\"uuid\":\"").append(service.getUuid()).append("\",");
            sb.append("\"type\":\"").append(service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY
                    ? "primary" : "secondary").append("\",");
            sb.append("\"characteristics_count\":").append(service.getCharacteristics().size());

            // Include characteristics
            sb.append(",\"characteristics\":[");
            boolean cFirst = true;
            for (BluetoothGattCharacteristic chr : service.getCharacteristics()) {
                if (!cFirst) sb.append(",");
                cFirst = false;
                sb.append("{");
                sb.append("\"uuid\":\"").append(chr.getUuid()).append("\",");
                sb.append("\"properties\":{");
                sb.append("\"read\":").append((chr.getProperties() & 0x02) != 0).append(",");
                sb.append("\"write\":").append((chr.getProperties() & 0x08) != 0).append(",");
                sb.append("\"notify\":").append((chr.getProperties() & 0x10) != 0);
                sb.append("}}");
            }
            sb.append("]");

            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String hexToBytesArray(String hex) {
        if (hex == null || hex.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) sb.append(",");
            sb.append("0x").append(hex.substring(i, Math.min(i + 2, hex.length())));
        }
        sb.append("]");
        return sb.toString();
    }
}

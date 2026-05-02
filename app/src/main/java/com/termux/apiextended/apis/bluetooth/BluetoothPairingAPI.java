package com.termux.apiextended.apis.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.util.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth Pairing API — Pair, unpair, list bonded devices.
 *
 * Methods:
 *   pair       — Pair with a device by MAC address
 *   unpair     — Remove pairing with a device
 *   paired     — List all bonded/paired devices
 *   bondstate  — Get bond state of a specific device
 */
public class BluetoothPairingAPI implements IApiModule {

    private static final String TAG = "BluetoothPairingAPI";

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.BT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "bt_pair_" + System.currentTimeMillis();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return CommandDispatcher.buildError(requestId, "NO_BLUETOOTH",
                    "Device does not support Bluetooth");
        }

        switch (method) {
            case "pair":
                return doPair(context, requestId, params, adapter);
            case "unpair":
                return doUnpair(context, requestId, params, adapter);
            case "paired":
                return doPaired(requestId, adapter);
            case "bondstate":
                return doBondState(requestId, params, adapter);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown bt.pair method: " + method);
        }
    }

    /**
     * Pair with a Bluetooth device by MAC address.
     * Uses reflection to call createBond() or the modern API on Android 10+.
     */
    private String doPair(Context context, String id, String params,
                          BluetoothAdapter adapter) {
        String address = CommandDispatcher.extractString(params, "address");
        if (address == null || address.isEmpty()) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'address' parameter (MAC address)");
        }

        if (!adapter.isEnabled()) {
            return CommandDispatcher.buildError(id, "BT_DISABLED",
                    "Bluetooth must be enabled first");
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);

            // Check current bond state
            int bondState;
            try { bondState = device.getBondState(); } catch (SecurityException e) {
                return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
            }

            if (bondState == BluetoothDevice.BOND_BONDED) {
                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"bond_state\":\"bonded\","
                  + "\"message\":\"Device already paired\"}");
            }

            if (bondState == BluetoothDevice.BOND_BONDING) {
                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"bond_state\":\"bonding\","
                  + "\"message\":\"Pairing already in progress\"}");
            }

            // Use createBond() (available since API 19)
            boolean initiated = device.createBond();

            if (initiated) {
                // Wait for bond state change
                BondStateReceiver receiver = new BondStateReceiver(address);
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                context.registerReceiver(receiver, filter);

                boolean result = receiver.waitForBond(30, TimeUnit.SECONDS);

                context.unregisterReceiver(receiver);

                if (result) {
                    return CommandDispatcher.buildResponse(id,
                        "{\"address\":\"" + address + "\","
                      + "\"bond_state\":\"bonded\","
                      + "\"message\":\"Successfully paired with " + address + "\"}");
                } else {
                    return CommandDispatcher.buildError(id, "PAIRING_FAILED",
                            "Pairing with " + address + " failed or timed out");
                }
            } else {
                return CommandDispatcher.buildError(id, "PAIRING_INIT_FAILED",
                        "Failed to initiate pairing with " + address);
            }

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "PAIRING_ERROR", e.getMessage());
        }
    }

    /**
     * Unpair / remove bond with a device.
     */
    private String doUnpair(Context context, String id, String params,
                            BluetoothAdapter adapter) {
        String address = CommandDispatcher.extractString(params, "address");
        if (address == null || address.isEmpty()) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'address' parameter");
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);

            int bondState;
            try { bondState = device.getBondState(); } catch (SecurityException e) {
                return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
            }

            if (bondState != BluetoothDevice.BOND_BONDED) {
                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"message\":\"Device not paired, nothing to remove\"}");
            }

            // Remove bond via reflection
            java.lang.reflect.Method removeBond =
                    BluetoothDevice.class.getMethod("removeBond");
            boolean removed = (Boolean) removeBond.invoke(device);

            if (removed) {
                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"message\":\"Device unpaired successfully\"}");
            } else {
                return CommandDispatcher.buildError(id, "UNPAIR_FAILED",
                        "Failed to unpair " + address);
            }

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "UNPAIR_ERROR", e.getMessage());
        }
    }

    /**
     * List all bonded/paired devices with detailed info.
     */
    private String doPaired(String id, BluetoothAdapter adapter) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                if (!first) sb.append(",");
                first = false;

                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = "unknown"; }

                String bondStateStr;
                try {
                    switch (device.getBondState()) {
                        case BluetoothDevice.BOND_BONDED: bondStateStr = "bonded"; break;
                        case BluetoothDevice.BOND_BONDING: bondStateStr = "bonding"; break;
                        case BluetoothDevice.BOND_NONE: bondStateStr = "none"; break;
                        default: bondStateStr = "unknown"; break;
                    }
                } catch (SecurityException e) { bondStateStr = "unknown"; }

                String typeStr;
                try {
                    switch (device.getType()) {
                        case BluetoothDevice.DEVICE_TYPE_CLASSIC: typeStr = "Classic"; break;
                        case BluetoothDevice.DEVICE_TYPE_LE: typeStr = "BLE"; break;
                        case BluetoothDevice.DEVICE_TYPE_DUAL: typeStr = "Dual"; break;
                        default: typeStr = "Unknown"; break;
                    }
                } catch (SecurityException e) { typeStr = "Unknown"; }

                sb.append("{");
                sb.append("\"name\":\"").append(PermissionManager.escapeJson(name)).append("\",");
                sb.append("\"address\":\"").append(device.getAddress()).append("\",");
                sb.append("\"bond_state\":\"").append(bondStateStr).append("\",");
                sb.append("\"type\":\"").append(typeStr).append("\"");
                sb.append("}");
            }
        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION",
                    "Cannot list bonded devices: " + e.getMessage());
        }

        sb.append("]");

        return CommandDispatcher.buildResponse(id,
            "{\"devices\":" + sb.toString() + "}");
    }

    /**
     * Get bond state of a specific device.
     */
    private String doBondState(String id, String params, BluetoothAdapter adapter) {
        String address = CommandDispatcher.extractString(params, "address");
        if (address == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'address' parameter");
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            int state = device.getBondState();

            String stateStr;
            switch (state) {
                case BluetoothDevice.BOND_NONE: stateStr = "none"; break;
                case BluetoothDevice.BOND_BONDING: stateStr = "bonding"; break;
                case BluetoothDevice.BOND_BONDED: stateStr = "bonded"; break;
                default: stateStr = "unknown"; break;
            }

            return CommandDispatcher.buildResponse(id,
                "{\"address\":\"" + address + "\","
              + "\"bond_state\":\"" + stateStr + "\"}");

        } catch (SecurityException e) {
            return CommandDispatcher.buildError(id, "SECURITY_EXCEPTION", e.getMessage());
        }
    }

    /**
     * BroadcastReceiver that waits for a specific device to reach BOND_BONDED state.
     */
    private static class BondStateReceiver extends BroadcastReceiver {
        private final String targetAddress;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean bonded = false;

        BondStateReceiver(String address) {
            this.targetAddress = address;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && targetAddress.equals(device.getAddress())) {
                    int newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                    if (newState == BluetoothDevice.BOND_BONDED) {
                        bonded = true;
                        latch.countDown();
                    } else if (newState == BluetoothDevice.BOND_NONE) {
                        // Bonding failed
                        latch.countDown();
                    }
                }
            }
        }

        boolean waitForBond(long timeout, TimeUnit unit) throws InterruptedException {
            latch.await(timeout, unit);
            return bonded;
        }
    }
}

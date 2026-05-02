package com.termux.apiextended.apis.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.util.PermissionManager;

import java.util.List;

/**
 * Bluetooth Audio API — A2DP audio control, device info.
 *
 * Methods:
 *   a2dp.info     — Current A2DP device info
 *   a2dp.devices   — Available audio devices
 *   a2dp.codec     — Current codec info
 *   hfp.info       — Hands-free profile info
 *   volume         — Get/set Bluetooth volume
 */
public class BluetoothAudioAPI implements IApiModule {

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.BT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "bt_audio_" + System.currentTimeMillis();

        switch (method) {
            case "a2dp.info":
                return doA2dpInfo(context, requestId);
            case "a2dp.devices":
                return doA2dpDevices(context, requestId);
            case "hfp.info":
                return doHfpInfo(context, requestId);
            case "volume":
                return doVolume(context, requestId, params);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown bt.audio method: " + method);
        }
    }

    /**
     * Get current A2DP connection info.
     */
    private String doA2dpInfo(Context context, String id) {
        ProfileConnector connector = new ProfileConnector(context, BluetoothProfile.A2DP);
        try {
            if (!connector.connect(10) || connector.getProfile() == null) {
                return CommandDispatcher.buildError(id, "A2DP_UNAVAILABLE",
                        "A2DP profile not available");
            }

            BluetoothProfile profile = connector.getProfile();
            List<BluetoothDevice> devices = profile.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED});

            if (devices.isEmpty()) {
                return CommandDispatcher.buildResponse(id,
                    "{\"connected\":false,\"message\":\"No A2DP device connected\"}");
            }

            BluetoothDevice device = devices.get(0);
            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = "unknown"; }

            return CommandDispatcher.buildResponse(id,
                "{\"connected\":true,"
              + "\"device\":{"
              + "\"name\":\"" + PermissionManager.escapeJson(name) + "\","
              + "\"address\":\"" + device.getAddress() + "\""
              + "},"
              + "\"profile\":\"A2DP\","
              + "\"state\":\"connected\"}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "A2DP_INFO_FAILED", e.getMessage());
        } finally {
            connector.close();
        }
    }

    /**
     * List all A2DP-capable devices (connected + available).
     */
    private String doA2dpDevices(Context context, String id) {
        ProfileConnector connector = new ProfileConnector(context, BluetoothProfile.A2DP);
        try {
            if (!connector.connect(10) || connector.getProfile() == null) {
                return CommandDispatcher.buildResponse(id, "{\"devices\":[]}");
            }

            BluetoothProfile profile = connector.getProfile();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;

            for (BluetoothDevice device : profile.getConnectedDevices()) {
                if (!first) sb.append(",");
                first = false;
                String name;
                try { name = device.getName(); } catch (SecurityException e) { name = "unknown"; }
                sb.append("{")
                  .append("\"name\":\"").append(PermissionManager.escapeJson(name)).append("\",")
                  .append("\"address\":\"").append(device.getAddress()).append("\",")
                  .append("\"state\":\"connected\"")
                  .append("}");
            }

            sb.append("]");

            return CommandDispatcher.buildResponse(id,
                "{\"devices\":" + sb.toString() + "}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "A2DP_DEVICES_FAILED", e.getMessage());
        } finally {
            connector.close();
        }
    }

    /**
     * Get hands-free profile info.
     */
    private String doHfpInfo(Context context, String id) {
        ProfileConnector connector = new ProfileConnector(context, BluetoothProfile.HEADSET);
        try {
            if (!connector.connect(10) || connector.getProfile() == null) {
                return CommandDispatcher.buildResponse(id,
                    "{\"connected\":false,\"message\":\"HFP profile not available\"}");
            }

            BluetoothProfile profile = connector.getProfile();
            List<BluetoothDevice> devices = profile.getDevicesMatchingConnectionStates(
                    new int[]{BluetoothProfile.STATE_CONNECTED});

            if (devices.isEmpty()) {
                return CommandDispatcher.buildResponse(id,
                    "{\"connected\":false,\"message\":\"No HFP device connected\"}");
            }

            BluetoothDevice device = devices.get(0);
            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = "unknown"; }

            // Check audio state (is a call active?)
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            boolean isBluetoothScoOn = am.isBluetoothScoOn();
            boolean isBluetoothA2dpOn = am.isBluetoothA2dpOn();

            return CommandDispatcher.buildResponse(id,
                "{\"connected\":true,"
              + "\"device\":{"
              + "\"name\":\"" + PermissionManager.escapeJson(name) + "\","
              + "\"address\":\"" + device.getAddress() + "\""
              + "},"
              + "\"sco_on\":" + isBluetoothScoOn + ","
              + "\"a2dp_on\":" + isBluetoothA2dpOn + ","
              + "\"profile\":\"HEADSET\"}");

        } catch (Exception e) {
            return CommandDispatcher.buildError(id, "HFP_INFO_FAILED", e.getMessage());
        } finally {
            connector.close();
        }
    }

    /**
     * Get or set Bluetooth volume.
     */
    private String doVolume(Context context, String id, String params) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        int setVolume = CommandDispatcher.extractInt(params, "volume", -1);
        int streamType = AudioManager.STREAM_MUSIC;

        if (setVolume >= 0) {
            int maxVolume = am.getStreamMaxVolume(streamType);
            int clamped = Math.max(0, Math.min(maxVolume, setVolume));
            am.setStreamVolume(streamType, clamped, 0);

            return CommandDispatcher.buildResponse(id,
                "{\"volume\":" + clamped + ","
              + "\"max_volume\":" + maxVolume + ","
              + "\"message\":\"Volume set\"}");
        } else {
            int currentVolume = am.getStreamVolume(streamType);
            int maxVolume = am.getStreamMaxVolume(streamType);

            return CommandDispatcher.buildResponse(id,
                "{\"volume\":" + currentVolume + ","
              + "\"max_volume\":" + maxVolume + ","
              + "\"percent\":" + (maxVolume > 0 ? (currentVolume * 100 / maxVolume) : 0) + "}");
        }
    }

    /**
     * Reuse ProfileConnector from BluetoothConnectionAPI.
     */
    private static class ProfileConnector implements BluetoothProfile.ServiceListener {
        private final Context context;
        private final int profileType;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        private volatile BluetoothProfile profile;

        ProfileConnector(Context context, int profileType) {
            this.context = context;
            this.profileType = profileType;
        }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            this.profile = proxy;
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            this.profile = null;
        }

        boolean connect(long timeoutSeconds) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) return false;
                adapter.getProfileProxy(context, this, profileType);
                return latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                return false;
            }
        }

        void close() {
            if (profile != null) {
                try { profile.close(); } catch (Exception ignored) {}
            }
        }

        BluetoothProfile getProfile() { return profile; }
    }
}

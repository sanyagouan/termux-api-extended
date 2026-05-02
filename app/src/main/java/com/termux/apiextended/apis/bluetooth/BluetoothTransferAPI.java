package com.termux.apiextended.apis.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.termux.apiextended.IApiModule;
import com.termux.apiextended.CommandDispatcher;
import com.termux.apiextended.util.PermissionManager;

import android.content.Intent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Bluetooth Transfer API — Send/receive files via OBEX OPP.
 *
 * Methods:
 *   send.file    — Send a file to a paired device
 *   receive.info — Info about received files
 *
 * Note: Android removed the public OBEX API in Android 10+.
 * For modern devices, this uses BluetoothSocket directly for data transfer,
 * and documents the OBEX limitation.
 */
public class BluetoothTransferAPI implements IApiModule {

    private static final String TAG = "BluetoothTransferAPI";

    // OBEX Object Push Profile UUID
    private static final UUID OBEX_OPP_UUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB");
    // Serial Port Profile UUID (for raw data)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    public String execute(Context context, String method, String params) {
        List<String> missing = PermissionManager.checkPermissions(context, PermissionManager.BT_PERMISSIONS);
        if (!missing.isEmpty()) {
            return PermissionManager.buildPermissionError("unknown", missing);
        }

        String requestId = CommandDispatcher.extractString(params, "id");
        if (requestId == null) requestId = "bt_transfer_" + System.currentTimeMillis();

        switch (method) {
            case "send.file":
                return doSendFile(context, requestId, params);
            case "send.data":
                return doSendData(context, requestId, params);
            case "receive.info":
                return doReceiveInfo(requestId);
            default:
                return CommandDispatcher.buildError(requestId, "UNKNOWN_METHOD",
                        "Unknown bt.transfer method: " + method);
        }
    }

    /**
     * Send a file to a paired Bluetooth device via SPP (Serial Port Profile).
     *
     * For OBEX OPP (proper file transfer with progress, filename, MIME type),
     * Android removed the public API. On Android < 10, the system handles OPP
     * via Intent: ACTION_SEND with BT prefix.
     *
     * This implementation uses raw Bluetooth socket for data transfer.
     */
    private String doSendFile(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String filePath = CommandDispatcher.extractString(params, "file_path");
        String mimeType = CommandDispatcher.extractString(params, "mime_type");

        if (address == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'address' parameter");
        }
        if (filePath == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS", "Missing 'file_path' parameter");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return CommandDispatcher.buildError(id, "BT_UNAVAILABLE", "Bluetooth not available");
        }

        // Try system OBEX OPP via Intent (works on most devices)
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                return CommandDispatcher.buildError(id, "FILE_NOT_FOUND",
                        "File not found: " + filePath);
            }

            Intent sendIntent = new Intent(android.content.Intent.ACTION_SEND);
            sendIntent.setType(mimeType != null ? mimeType : "application/octet-stream");
            sendIntent.putExtra(android.content.Intent.EXTRA_STREAM,
                    android.net.Uri.fromFile(file));
            sendIntent.setPackage("com.android.bluetooth");

            // Try to set the BT device
            BluetoothDevice device = adapter.getRemoteDevice(address);
            sendIntent.putExtra("android.bluetooth.device.extra.DEVICE", device);

            sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(sendIntent);

            return CommandDispatcher.buildResponse(id,
                "{\"address\":\"" + address + "\","
              + "\"file\":\"" + PermissionManager.escapeJson(filePath) + "\","
              + "\"size_bytes\":" + file.length() + ","
              + "\"method\":\"Intent_OPP\","
              + "\"message\":\"File transfer initiated via system OBEX. "
              + "Check the Bluetooth transfer notification on the device.\"}");

        } catch (Exception e) {
            // Fallback: SPP raw socket transfer
            return sendViaSPP(id, address, filePath);
        }
    }

    /**
     * Send raw data bytes to a device via SPP.
     */
    private String doSendData(Context context, String id, String params) {
        String address = CommandDispatcher.extractString(params, "address");
        String data = CommandDispatcher.extractString(params, "data");

        if (address == null || data == null) {
            return CommandDispatcher.buildError(id, "INVALID_PARAMS",
                    "Missing 'address' or 'data' parameter");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return CommandDispatcher.buildError(id, "BT_UNAVAILABLE", "Bluetooth not available");
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();

            try {
                OutputStream out = socket.getOutputStream();
                out.write(data.getBytes("UTF-8"));
                out.flush();

                // Read response
                InputStream in = socket.getInputStream();
                in.mark(1024);
                byte[] buffer = new byte[1024];
                int read = in.read(buffer);

                String response = read > 0 ? new String(buffer, 0, read, "UTF-8") : "";

                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"bytes_sent\":" + data.length() + ","
                  + "\"response\":\"" + PermissionManager.escapeJson(response) + "\","
                  + "\"method\":\"SPP\"}");

            } finally {
                socket.close();
            }

        } catch (IOException e) {
            return CommandDispatcher.buildError(id, "SEND_FAILED",
                    "Failed to send data: " + e.getMessage());
        }
    }

    /**
     * Fallback: Send file via SPP socket.
     */
    private String sendViaSPP(String id, String address, String filePath) {
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            return CommandDispatcher.buildError(id, "FILE_NOT_FOUND",
                    "File not found: " + filePath);
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = adapter.getRemoteDevice(address);
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();

            try {
                OutputStream out = socket.getOutputStream();
                java.io.InputStream in = new java.io.FileInputStream(file);

                byte[] buffer = new byte[4096];
                long totalSent = 0;
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalSent += bytesRead;
                }

                out.flush();

                return CommandDispatcher.buildResponse(id,
                    "{\"address\":\"" + address + "\","
                  + "\"file\":\"" + PermissionManager.escapeJson(filePath) + "\","
                  + "\"bytes_sent\":" + totalSent + ","
                  + "\"method\":\"SPP\","
                  + "\"message\":\"File sent via SPP socket\"}");

            } finally {
                socket.close();
            }

        } catch (IOException e) {
            return CommandDispatcher.buildError(id, "SPP_TRANSFER_FAILED",
                    "SPP transfer failed: " + e.getMessage()
                    + ". Ensure the receiving device has SPP server running.");
        }
    }

    /**
     * Info about Bluetooth receive capabilities.
     */
    private String doReceiveInfo(String id) {
        return CommandDispatcher.buildResponse(id,
            "{\"obex_opp_available\":true,"
          + "\"spp_uuid\":\"" + SPP_UUID + "\","
          + "\"obex_opp_uuid\":\"" + OBEX_OPP_UUID + "\","
          + "\"note\":\"File reception is handled by the Android Bluetooth system service. "
          + "Received files appear in /sdcard/Download/Bluetooth/. "
          + "Programmatic receive requires a running SPP server socket.\"}");
    }
}

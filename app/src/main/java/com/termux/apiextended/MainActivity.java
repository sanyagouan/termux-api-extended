package com.termux.apiextended;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity — Manages permissions and starts/stops the API service.
 *
 * Layout is minimal: shows server status and a start/stop button.
 * The real work happens in the background ApiService + TcpServer.
 */
public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);

        // Check and request permissions
        requestAllPermissions();

        // Start button
        findViewById(R.id.btn_start).setOnClickListener(v -> startService());

        // Stop button
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopService());

        // Check if service is already running
        updateStatus();
    }

    /**
     * Request all required permissions at once.
     */
    private void requestAllPermissions() {
        List<String> needed = new ArrayList<>();

        // WiFi permissions
        String[] wifiPerms = {
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        };
        for (String perm : wifiPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] btPerms = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            };
            for (String perm : btPerms) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(perm);
                }
            }
        } else {
            String[] btPerms = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            };
            for (String perm : btPerms) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(perm);
                }
            }
        }

        // Nearby WiFi devices (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES")
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.NEARBY_WIFI_DEVICES");
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            int granted = 0;
            int denied = 0;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) granted++;
                else denied++;
            }
            Toast.makeText(this,
                    granted + " permissions granted, " + denied + " denied",
                    Toast.LENGTH_LONG).show();
            updateStatus();
        }
    }

    private void startService() {
        Intent intent = new Intent(this, ApiService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText("Starting...");
        statusText.postDelayed(this::updateStatus, 1000);
    }

    private void stopService() {
        Intent intent = new Intent(this, ApiService.class);
        intent.setAction("STOP");
        startService(intent);
        statusText.setText("Stopped");
    }

    private void updateStatus() {
        // Check if we can connect to the TCP server
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 8080), 500);
            statusText.setText("✅ Running on 127.0.0.1:8080");
            s.close();
        } catch (Exception e) {
            statusText.setText("❌ Not running");
        }
    }
}

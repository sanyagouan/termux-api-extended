package com.termux.apiextended;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import com.termux.apiextended.util.PermissionManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP Server that listens on localhost and dispatches commands from Termux.
 *
 * Protocol:
 *   - Client connects to localhost:8080
 *   - Client sends JSON command (newline-delimited)
 *   - Server responds with JSON (newline-delimited)
 *   - Connection stays open for streaming support
 *   - Client sends "quit\n" or closes socket to disconnect
 *
 * Security:
 *   - Listens ONLY on localhost (127.0.0.1)
 *   - No authentication required (localhost-only is the security model)
 *   - SELinux blocks external connections on unrooted devices
 */
public class TcpServer {

    private static final String TAG = "TcpServer";
    private static final int DEFAULT_PORT = 8080;

    private final Context context;
    private final CommandDispatcher dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private int port;

    public TcpServer(Context context) {
        this.context = context.getApplicationContext();
        this.dispatcher = new CommandDispatcher(context);
        this.port = DEFAULT_PORT;
    }

    /**
     * Start the TCP server on the configured port.
     *
     * @return true if started successfully
     */
    public boolean start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Server already running");
            return true;
        }

        try {
            serverSocket = new ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"));
            // Allow socket reuse
            serverSocket.setReuseAddress(true);

            threadPool = Executors.newFixedThreadPool(4);

            // Accept connections in a background thread
            new Thread(this::acceptLoop, "TcpServer-Accept").start();

            Log.i(TAG, "TCP server started on 127.0.0.1:" + port);
            return true;

        } catch (Exception e) {
            running.set(false);
            Log.e(TAG, "Failed to start TCP server", e);
            return false;
        }
    }

    /**
     * Start the TCP server on a custom port.
     *
     * @param port Port number to listen on
     * @return true if started successfully
     */
    public boolean start(int port) {
        this.port = port;
        return start();
    }

    /**
     * Stop the TCP server and close all connections.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing server socket", e);
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        Log.i(TAG, "TCP server stopped");
    }

    /**
     * Check if the server is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get the port the server is listening on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Main accept loop — accepts client connections and dispatches to handler threads.
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30000); // 30s read timeout
                threadPool.submit(() -> handleClient(client));
            } catch (java.net.SocketException e) {
                // Expected when server socket is closed during stop()
                if (running.get()) {
                    Log.w(TAG, "Socket exception in accept loop", e);
                }
            } catch (Exception e) {
                if (running.get()) {
                    Log.e(TAG, "Error accepting connection", e);
                }
            }
        }
    }

    /**
     * Handle a single client connection.
     * Reads newline-delimited JSON commands and writes JSON responses.
     */
    private void handleClient(Socket client) {
        String clientAddr = client.getInetAddress().getHostAddress();
        Log.d(TAG, "Client connected: " + clientAddr);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = client.getOutputStream()) {

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) continue;
                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) {
                    break;
                }

                Log.d(TAG, "Received: " + line);

                // Dispatch command
                String response = dispatcher.dispatch(line);
                out.write(response.getBytes("UTF-8"));
                out.write('\n');
                out.flush();

                Log.d(TAG, "Sent: " + response.length() + " bytes");
            }

        } catch (java.net.SocketTimeoutException e) {
            Log.d(TAG, "Client timeout: " + clientAddr);
        } catch (Exception e) {
            Log.e(TAG, "Error handling client: " + clientAddr, e);
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {}
            Log.d(TAG, "Client disconnected: " + clientAddr);
        }
    }
}

package com.example.kafkadup.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TcpProxy that can bind to a specific port or 0 (ephemeral).
 * Call start(); then getListenPort() to find the actual port.
 */
public class TcpProxy {
    private final int requestedPort; // 0 for ephemeral
    private final String remoteHost;
    private final int remotePort;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private final AtomicBoolean blockUpstream = new AtomicBoolean(false);
    private final Set<Socket> activeClientSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TcpProxy(int listenPort, String remoteHost, int remotePort) {
        this.requestedPort = listenPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public void start() throws IOException {
        // bind to requestedPort (0 allowed). serverSocket.getLocalPort() returns actual port.
        serverSocket = new ServerSocket(requestedPort);
        pool.submit(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    Socket remote = new Socket(remoteHost, remotePort);
                    activeClientSockets.add(client);
                    pool.submit(() -> {
                        try {
                            forward(client.getInputStream(), remote.getOutputStream(), client, remote);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    pool.submit(() -> {
                        try {
                            forwardUpstream(remote.getInputStream(), client.getOutputStream(), client, remote);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            } catch (IOException e) {
                // exit accept loop on socket close or error
            }
        });
    }

    public int getListenPort() {
        return (serverSocket != null) ? serverSocket.getLocalPort() : -1;
    }

    private void forward(InputStream in, OutputStream out, Socket client, Socket remote) {
        byte[] buf = new byte[8192];
        try {
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
            try { out.close(); } catch (Exception ignore) {}
            try { in.close(); } catch (Exception ignore) {}
            activeClientSockets.remove(client);
        }
    }

    private void forwardUpstream(InputStream in, OutputStream out, Socket client, Socket remote) {
        byte[] buf = new byte[8192];
        try {
            int r;
            while ((r = in.read(buf)) != -1) {
                if (blockUpstream.get()) {
                    try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
            try { out.close(); } catch (Exception ignore) {}
            try { in.close(); } catch (Exception ignore) {}
            activeClientSockets.remove(client);
        }
    }

    public void setBlockUpstream(boolean block) {
        blockUpstream.set(block);
    }

    public boolean isBlockUpstream() {
        return blockUpstream.get();
    }

    public void stop() {
        try { serverSocket.close(); } catch (Exception ignored) {}
        try { pool.shutdownNow(); } catch (Exception ignored) {}
        for (Socket s : activeClientSockets) {
            try { s.close(); } catch (Exception ignored) {}
        }
        activeClientSockets.clear();
    }

    private void closeQuietly(Socket s) {
        try { if (s != null && !s.isClosed()) s.close(); } catch (Exception ignore) {}
    }
}

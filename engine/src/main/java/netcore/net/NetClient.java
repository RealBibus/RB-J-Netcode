package netcore.net;

import netcore.sync.ClockSync;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

public final class NetClient implements Transport.Listener, AutoCloseable {

    public interface Listener {
        void onMessage(byte[] payload);

        void onClosed(String reason);

        default void onLinkLost(String reason, long retryForMs) {
        }

        default void onLinkRestored() {
        }
    }

    private final String host;
    private final int port;
    private final NetConfig config;
    private final Listener listener;
    private final ClockSync clock = new ClockSync();

    private volatile Transport transport;
    private volatile boolean closed;
    private volatile boolean reconnecting;
    private volatile int localPort = -1;
    private volatile String protocol = "?";
    private volatile long lastPongNanos;
    private volatile long lastPacketNanos;
    private Thread heartbeat;

    private NetClient(String host, int port, NetConfig config, Listener listener) {
        this.host = host;
        this.port = port;
        this.config = config;
        this.listener = listener;
    }

    public static NetClient connect(String host, int port, Listener listener) throws IOException {
        return connect(host, port, NetConfig.DEFAULT, listener);
    }

    public static NetClient connect(String host, int port, NetConfig config, Listener listener) throws IOException {
        NetClient client = new NetClient(host, port, config, listener);
        Transport transport = client.openTransport(false, config.connectTimeoutMs());
        client.activate(transport);
        client.startHeartbeat();
        return client;
    }

    private Transport openTransport(boolean reusePort, int timeoutMs) throws IOException {
        IOException first = null;
        if (config.preferred() == NetConfig.Protocol.UDP) {
            try {
                return openUdp(reusePort, timeoutMs);
            } catch (IOException e) {
                first = e;
                if (!config.allowTcp()) throw e;
            }
        }
        try {
            return openTcp(timeoutMs);
        } catch (IOException e) {
            if (first != null) e.addSuppressed(first);
            throw e;
        }
    }

    private Transport openTcp(int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), Math.max(400, timeoutMs));
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        return new TcpTransport(socket, this, config.compression());
    }

    private Transport openUdp(boolean reusePort, int timeoutMs) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        boolean bound = false;
        if (reusePort && localPort > 0) {
            try {
                socket.bind(new InetSocketAddress(localPort));
                bound = true;
            } catch (IOException ignored) {
            }
        }
        if (!bound) socket.bind(new InetSocketAddress(0));

        try {
            socket.connect(InetAddress.getByName(host), port);
            socket.setSoTimeout(120);

            ByteBuffer connect = ByteBuffer.allocate(9);
            connect.put(UdpPeer.T_CONNECT).putInt(0).putInt(0);

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean accepted = false;
            byte[] buffer = new byte[64];
            while (System.currentTimeMillis() < deadline) {
                socket.send(new DatagramPacket(connect.array(), 9));
                try {
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    if (reply.getLength() >= 1 && buffer[0] == UdpPeer.T_ACCEPT) {
                        accepted = true;
                        break;
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
            if (!accepted) throw new IOException("UDP: server did not respond");
            socket.setSoTimeout(0);
            localPort = socket.getLocalPort();
            return new UdpPeer(socket, null, config, this);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private void activate(Transport next) {
        transport = next;
        protocol = next.protocol();
        lastPacketNanos = System.nanoTime();
        next.start();
    }

    private void startHeartbeat() {
        heartbeat = new Thread(() -> {
            while (!closed) {
                try {
                    if (!reconnecting) {
                        Transport t = transport;
                        if (t != null && t.isOpen()) {
                            long now = System.nanoTime();
                            ByteBuffer buf = ByteBuffer.allocate(8);
                            buf.putLong(now);
                            clock.onPingSent(now);
                            t.send(new Packet(Packet.PING, 0, buf.array(), false));
                            long last = lastPacketNanos;
                            if (last != 0 && now - last > config.silentLossMs() * 1_000_000L) {
                                startReconnect("link timeout");
                            }
                        }
                    }
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    startReconnect(e.getMessage());
                }
            }
        }, "netcore-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    private void startReconnect(String reason) {
        synchronized (this) {
            if (closed || reconnecting) return;
            reconnecting = true;
            Transport old = transport;
            transport = null;
            if (old != null) old.close();
        }
        listener.onLinkLost(reason == null || reason.isBlank() ? "link lost" : reason,
                config.reconnectWindowMs());
        Thread t = new Thread(this::reconnectLoop, "netcore-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void reconnectLoop() {
        long deadline = System.nanoTime() + config.reconnectWindowMs() * 1_000_000L;
        int attempt = 0;
        while (!closed && System.nanoTime() < deadline) {
            attempt++;
            try {
                Transport next = openTransport(true, Math.min(450, config.connectTimeoutMs()));
                synchronized (this) {
                    if (closed) {
                        next.close();
                        return;
                    }
                    activate(next);
                    reconnecting = false;
                }
                listener.onLinkRestored();
                return;
            } catch (IOException ignored) {
            }
            long wait = Math.min(900, 100L + 80L * attempt);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
        finish("Connection lost");
    }

    private void finish(String reason) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            reconnecting = false;
            transport = null;
        }
        if (heartbeat != null) heartbeat.interrupt();
        listener.onClosed(reason == null || reason.isBlank() ? "connection closed" : reason);
    }

    @Override
    public void onPacket(Packet packet) {
        lastPacketNanos = System.nanoTime();
        Transport t = transport;
        if (t == null) return;
        if (packet.type == Packet.PING) {
            if (packet.payload.length >= 8) {
                long sent = ByteBuffer.wrap(packet.payload).getLong();
                ByteBuffer reply = ByteBuffer.allocate(16);
                reply.putLong(sent);
                reply.putLong(System.nanoTime());
                t.send(new Packet(Packet.PONG, 0, reply.array(), false));
            }
            return;
        }
        if (packet.type == Packet.PONG && packet.payload.length >= 16) {
            ByteBuffer buf = ByteBuffer.wrap(packet.payload);
            long sent = buf.getLong();
            long server = buf.getLong();
            clock.onPong(sent, server, System.nanoTime());
            lastPongNanos = System.nanoTime();
            return;
        }
        if (packet.type == Packet.DATA) {
            listener.onMessage(packet.payload);
        }
    }

    @Override
    public void onClosed(String reason) {
        synchronized (this) {
            if (closed || reconnecting) return;
        }
        if (!config.autoReconnect()) {
            finish(reason);
            return;
        }
        startReconnect(reason);
    }

    public void send(byte[] payload) {
        Transport t = transport;
        if (t != null) t.send(new Packet(Packet.DATA, 0, payload, true));
    }

    public void sendUnreliable(byte[] payload) {
        Transport t = transport;
        if (t != null) t.send(new Packet(Packet.DATA, 0, payload, false));
    }

    public ClockSync clock() {
        return clock;
    }

    public NetStats stats() {
        Transport t = transport;
        return t == null ? new NetStats() : t.stats();
    }

    public int rttMs() {
        return clock.rttMs();
    }

    public int jitterMs() {
        return clock.jitterMs();
    }

    public int lossPercent() {
        return clock.lossPercent();
    }

    public int quality() {
        return clock.quality();
    }

    public String protocol() {
        return protocol;
    }

    public boolean isOpen() {
        Transport t = transport;
        return !closed && !reconnecting && t != null && t.isOpen();
    }

    public boolean isReconnecting() {
        return reconnecting;
    }

    public String remote() {
        Transport t = transport;
        return t == null ? host + ":" + port : t.remote();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        Transport t;
        synchronized (this) {
            if (closed) return;
            closed = true;
            reconnecting = false;
            t = transport;
            transport = null;
        }
        if (heartbeat != null) heartbeat.interrupt();
        if (t != null) t.close();
    }
}

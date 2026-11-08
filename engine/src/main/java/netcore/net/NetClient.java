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

/**
 * Клиент движка. Пробует UDP, если сервер не ответил — откатывается на TCP
 * (когда это разрешено в {@link NetConfig}). Пинги и часы ведёт сам.
 */
public final class NetClient implements Transport.Listener, AutoCloseable {

    public interface Listener {
        void onMessage(byte[] payload);

        void onClosed(String reason);
    }

    private final Transport transport;
    private final Listener listener;
    private final ClockSync clock = new ClockSync();
    private final NetStats stats;

    private Thread heartbeat;
    private volatile boolean closed;
    private volatile String protocol = "?";
    private volatile long lastPongNanos;
    private volatile long lastPacketNanos;

    private NetClient(Transport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
        this.stats = transport.stats();
        this.protocol = transport.protocol();
    }

    public static NetClient connect(String host, int port, Listener listener) throws IOException {
        return connect(host, port, NetConfig.DEFAULT, listener);
    }

    public static NetClient connect(String host, int port, NetConfig config, Listener listener) throws IOException {
        if (config.preferred() == NetConfig.Protocol.UDP) {
            try {
                return connectUdp(host, port, config, listener);
            } catch (IOException udpError) {
                if (!config.allowTcp()) throw udpError;
            }
        }
        return connectTcp(host, port, config, listener);
    }

    private static NetClient connectTcp(String host, int port, NetConfig config, Listener listener) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), Math.max(1500, config.connectTimeoutMs()));
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        final NetClient[] holder = new NetClient[1];
        TcpTransport transport = new TcpTransport(socket, new Transport.Listener() {
            @Override
            public void onPacket(Packet packet) {
                holder[0].onPacket(packet);
            }

            @Override
            public void onClosed(String reason) {
                holder[0].onClosed(reason);
            }
        });
        NetClient client = new NetClient(transport, listener);
        holder[0] = client;
        transport.startReader();
        client.startHeartbeat();
        return client;
    }

    private static NetClient connectUdp(String host, int port, NetConfig config, Listener listener) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        socket.connect(InetAddress.getByName(host), port);
        socket.setSoTimeout(120);

        ByteBuffer connect = ByteBuffer.allocate(9);
        connect.put(UdpPeer.T_CONNECT).putInt(0).putInt(0);

        long deadline = System.currentTimeMillis() + config.connectTimeoutMs();
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
                // повторяем CONNECT
            }
        }
        if (!accepted) {
            socket.close();
            throw new IOException("UDP: сервер не ответил");
        }
        socket.setSoTimeout(0);
        final NetClient[] holder = new NetClient[1];
        UdpPeer peer = new UdpPeer(socket, null, config, new Transport.Listener() {
            @Override
            public void onPacket(Packet packet) {
                holder[0].onPacket(packet);
            }

            @Override
            public void onClosed(String reason) {
                holder[0].onClosed(reason);
            }
        });
        NetClient client = new NetClient(peer, listener);
        holder[0] = client;
        peer.start();
        client.startHeartbeat();
        return client;
    }

    private void startHeartbeat() {
        heartbeat = new Thread(() -> {
            while (!closed) {
                try {
                    long now = System.nanoTime();
                    ByteBuffer buf = ByteBuffer.allocate(8);
                    buf.putLong(now);
                    transport.send(new Packet(Packet.PING, 0, buf.array(), false));
                    long last = lastPacketNanos;
                    if (last != 0 && System.nanoTime() - last > 8_000_000_000L) {
                        onClosed("сервер не отвечает 8 с");
                        close();
                        return;
                    }
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "netcore-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    @Override
    public void onPacket(Packet packet) {
        lastPacketNanos = System.nanoTime();
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
        if (closed) return;
        closed = true;
        listener.onClosed(reason == null || reason.isBlank() ? "сервер закрыл соединение" : reason);
    }

    public void send(byte[] payload) {
        transport.send(new Packet(Packet.DATA, 0, payload, true));
    }

    /** Снимки состояния можно не досылать — потеря не критична. */
    public void sendUnreliable(byte[] payload) {
        transport.send(new Packet(Packet.DATA, 0, payload, false));
    }

    public ClockSync clock() {
        return clock;
    }

    public NetStats stats() {
        return stats;
    }

    /** "UDP" или "TCP" — чем реально connected. */
    public String protocol() {
        return protocol;
    }

    public boolean isOpen() {
        return !closed && transport.isOpen();
    }

    public String remote() {
        return transport.remote();
    }

    @Override
    public void close() {
        closed = true;
        if (heartbeat != null) heartbeat.interrupt();
        transport.close();
    }
}

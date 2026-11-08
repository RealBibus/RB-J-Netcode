package netcore.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сервер движка: UDP (основной) и TCP (запасной, если разрешён в {@link NetConfig}).
 * Служебные пинги отвечает сам — игре они не видны.
 */
public final class NetServer implements AutoCloseable {

    public interface Listener {
        void onConnect(Connection connection);

        void onMessage(Connection connection, byte[] payload);

        void onDisconnect(Connection connection, String reason);
    }

    private final int requestedPort;
    private final NetConfig config;
    private final Listener listener;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<SocketAddress, UdpPeer> udpPeers = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    private ServerSocket serverSocket;
    private DatagramSocket udpSocket;
    private Thread acceptThread;
    private Thread udpThread;
    private volatile boolean running;

    public NetServer(int port, Listener listener) {
        this(port, NetConfig.DEFAULT, listener);
    }

    public NetServer(int port, NetConfig config, Listener listener) {
        this.requestedPort = port;
        this.config = config;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        int port = requestedPort == 0 ? randomPort() : requestedPort;
        int attempts = requestedPort == 0 ? 12 : 1;
        IOException lastError = null;

        // UDP первым: Windows отдаёт разные диапазоны под TCP и UDP, и порт,
        // выбранный TCP, может быть запрещён для UDP (excludedportrange)
        for (int attempt = 0; attempt < attempts; attempt++) {
            ServerSocket tcp = null;
            DatagramSocket udp = null;
            try {
                if (config.listenUdp()) {
                    udp = new DatagramSocket(port);
                    udp.setReceiveBufferSize(256 * 1024);
                    port = udp.getLocalPort();
                }
                if (config.listenTcp()) {
                    try {
                        tcp = new ServerSocket();
                        tcp.setReuseAddress(true);
                        tcp.bind(new InetSocketAddress(port));
                        port = tcp.getLocalPort();
                    } catch (IOException e) {
                        if (udp == null || requestedPort == 0) throw e;
                        System.err.println("[netcore] TCP недоступен, работаем только на UDP: " + e.getMessage());
                        tcp = null;
                    }
                }
                serverSocket = tcp;
                udpSocket = udp;
                break;
            } catch (IOException e) {
                lastError = e;
                if (tcp != null) {
                    try {
                        tcp.close();
                    } catch (IOException ignored) {
                    }
                }
                if (udp != null) udp.close();
                if (attempt == attempts - 1) throw e;
                port = randomPort();
            }
        }        if (serverSocket == null && udpSocket == null) {
            throw lastError == null ? new IOException("не удалось занять порт") : lastError;
        }

        running = true;
        if (serverSocket != null) {
            acceptThread = new Thread(this::acceptLoop, "netcore-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
        if (udpSocket != null) {
            udpThread = new Thread(this::udpLoop, "netcore-udp");
            udpThread.setDaemon(true);
            udpThread.start();
        }
    }
    // ------------------------------------------------------------------
    // TCP
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                final Connection[] holder = new Connection[1];
                TcpTransport transport = new TcpTransport(socket, new Transport.Listener() {
                    @Override
                    public void onPacket(Packet packet) {
                        handlePacket(holder[0], packet);
                    }

                    @Override
                    public void onClosed(String reason) {
                        Connection c = holder[0];
                        if (c != null && connections.remove(c)) listener.onDisconnect(c, reason);
                    }
                });
                Connection connection = new Connection(ids.incrementAndGet(), transport);
                holder[0] = connection;
                connections.add(connection);
                transport.startReader();
                listener.onConnect(connection);
            } catch (IOException e) {
                if (running) System.err.println("[netcore] accept: " + e.getMessage());
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // UDP
    // ------------------------------------------------------------------

    private void udpLoop() {
        byte[] buffer = new byte[65535];
        while (running) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(datagram);
            } catch (IOException e) {
                break;
            }
            SocketAddress from = datagram.getSocketAddress();
            UdpPeer peer = udpPeers.get(from);
            if (peer != null) {
                peer.onDatagram(buffer, datagram.getLength());
                continue;
            }
            if (datagram.getLength() >= 1 && buffer[0] == UdpPeer.T_CONNECT) {
                acceptUdp(from);
            }
        }
    }

    private void acceptUdp(SocketAddress from) {
        final Connection[] holder = new Connection[1];
        UdpPeer peer = new UdpPeer(udpSocket, from, config, null);
        Connection connection = new Connection(ids.incrementAndGet(), peer);
        holder[0] = connection;
        peer.setListener(new Transport.Listener() {
            @Override
            public void onPacket(Packet packet) {
                handlePacket(holder[0], packet);
            }

            @Override
            public void onClosed(String reason) {
                Connection c = holder[0];
                udpPeers.remove(from);
                if (c != null && connections.remove(c)) listener.onDisconnect(c, reason);
            }
        });
        udpPeers.put(from, peer);
        connections.add(connection);
        peer.start();
        UdpPeer.sendAccept(udpSocket, from);
        listener.onConnect(connection);
    }

    // ------------------------------------------------------------------

    private void handlePacket(Connection connection, Packet packet) {
        if (connection == null) return;
        if (packet.type == Packet.PING) {
            long clientNanos = packet.payload.length >= 8
                    ? ByteBuffer.wrap(packet.payload).getLong() : 0L;
            ByteBuffer reply = ByteBuffer.allocate(16);
            reply.putLong(clientNanos);
            reply.putLong(System.nanoTime());
            connection.sendPacket(Packet.PONG, reply.array());
            return;
        }
        if (packet.type == Packet.DATA) {
            listener.onMessage(connection, packet.payload);
        }
    }

    /** Свободный порт вне системных диапазонов (там Windows отдаёт exclusion-списки). */
    private static int randomPort() {
        return 24000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20000);
    }

    public int port() {
        if (udpSocket != null) return udpSocket.getLocalPort();
        if (serverSocket != null) return serverSocket.getLocalPort();
        return requestedPort;
    }

    public NetConfig config() {
        return config;
    }

    public String protocols() {
        StringBuilder sb = new StringBuilder();
        if (udpSocket != null) sb.append("UDP");
        if (serverSocket != null) {
            if (sb.length() > 0) sb.append("+");
            sb.append("TCP");
        }
        return sb.toString();
    }

    public List<Connection> connections() {
        return connections;
    }

    public void broadcast(byte[] payload) {
        broadcast(payload, true);
    }

    public void broadcast(byte[] payload, boolean reliable) {
        for (Connection c : connections) {
            if (c.isOpen()) c.send(payload, reliable);
        }
    }

    public void send(Connection c, byte[] payload) {
        if (c != null && c.isOpen()) c.send(payload, true);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (udpSocket != null) udpSocket.close();
        for (Connection c : connections) c.close();
        connections.clear();
        udpPeers.clear();
    }

    @Override
    public void close() {
        stop();
    }
}

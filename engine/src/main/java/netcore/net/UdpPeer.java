package netcore.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * UDP-канал до одного пира: свои подтверждения, повторы и склейка фрагментов.
 *
 * <p>Формат датаграммы: {@code [тип:1][ackId:4][ackBits:4][тело]}.
 * Надёжные сообщения режутся на фрагменты {@code [msgId:4][фраг:1][всего:1][кусок]}
 * и повторяются, пока не придут подтверждения. Ненадёжные (снимки состояния)
 * уходят один раз: потеря не страшна, следующий снимок новее.</p>
 */
final class UdpPeer implements Transport {

    static final byte T_DATA_REL = 10;
    static final byte T_DATA_UNREL = 11;
    static final byte T_PING = 12;
    static final byte T_PONG = 13;
    static final byte T_CONNECT = 14;
    static final byte T_ACCEPT = 15;

    private static final int HEADER = 1 + 4 + 4;
    private static final int FRAG_HEADER = 4 + 1 + 1;

    private static final class Outgoing {
        final int id;
        final byte[][] fragments;
        long lastSent;
        int attempts;

        Outgoing(int id, byte[][] fragments) {
            this.id = id;
            this.fragments = fragments;
        }
    }

    private final DatagramSocket socket;
    private final SocketAddress remote;
    private final NetConfig config;
    private final NetStats stats = new NetStats();

    private final Object lock = new Object();
    private final Map<Integer, Outgoing> pending = new LinkedHashMap<>();
    private final Map<Integer, byte[][]> incoming = new HashMap<>();
    private final TreeMap<Integer, byte[]> ready = new TreeMap<>();

    private int nextId = 1;
    private int lastDelivered;
    private int ackBits;

    private volatile long lastRecv = System.nanoTime();
    private volatile boolean open = true;
    private volatile Transport.Listener listener;
    private volatile Runnable onClose;
    private Thread ticker;
    private final String remoteText;

    UdpPeer(DatagramSocket socket, SocketAddress remote, NetConfig config, Transport.Listener listener) {
        this.socket = socket;
        this.remote = remote;
        this.config = config;
        this.listener = listener;
        this.remoteText = remote == null ? "udp" : remote.toString();
    }

    void setListener(Transport.Listener listener) {
        this.listener = listener;
    }

    void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    void start() {
        ticker = new Thread(this::loop, "netcore-udp-peer");
        ticker.setDaemon(true);
        ticker.start();
        if (remote == null) {
            // клиент: сокет только наш, читаем сами (у сервера общий цикл на всех)
            Thread reader = new Thread(this::readLoop, "netcore-udp-read");
            reader.setDaemon(true);
            reader.start();
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[65535];
        while (open) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (IOException e) {
                if (open) closeWithReason("UDP: " + e.getMessage());
                return;
            }
            onDatagram(datagram.getData(), datagram.getLength());
        }
    }

    private void loop() {
        while (open) {
            long now = System.nanoTime();
            synchronized (lock) {
                resend(now);
            }
            if (now - lastRecv > config.deadAfterMs() * 1_000_000L) {
                closeWithReason("таймаут соединения");
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void resend(long now) {
        Iterator<Outgoing> it = pending.values().iterator();
        while (it.hasNext()) {
            Outgoing o = it.next();
            if (now - o.lastSent < config.resendMs() * 1_000_000L) continue;
            if (o.attempts > 80) {
                it.remove();
                closeWithReason("не удалось доставить сообщение");
                return;
            }
            sendFragments(o);
        }
    }

    // ------------------------------------------------------------------
    // отправка
    // ------------------------------------------------------------------

    @Override
    public void send(Packet packet) {
        if (!open) return;
        List<byte[]> datagrams = new ArrayList<>(1);
        synchronized (lock) {
            if (packet.type == Packet.DATA) {
                // очень крупный снимок лучше дослать с подтверждением, чем
                // надеяться, что IP-фрагментация доживёт
                if (packet.reliable || packet.payload.length > 8000) {
                    queueReliable(packet.payload);
                } else {
                    datagrams.add(build(T_DATA_UNREL, packet.payload));
                }
            } else {
                datagrams.add(build(packet.type == Packet.PING ? T_PING : T_PONG, packet.payload));
            }
        }
        for (byte[] d : datagrams) sendDatagram(d);
    }

    private void queueReliable(byte[] payload) {
        int chunkSize = Math.max(64, config.mtu() - HEADER - FRAG_HEADER);
        int count = Math.max(1, (payload.length + chunkSize - 1) / chunkSize);
        byte[][] fragments = new byte[count][];
        for (int i = 0; i < count; i++) {
            int from = i * chunkSize;
            int to = Math.min(payload.length, from + chunkSize);
            byte[] f = new byte[to - from];
            System.arraycopy(payload, from, f, 0, f.length);
            fragments[i] = f;
        }
        Outgoing o = new Outgoing(nextId++, fragments);
        pending.put(o.id, o);
        sendFragments(o);
    }

    private void sendFragments(Outgoing o) {
        for (int i = 0; i < o.fragments.length; i++) {
            byte[] f = o.fragments[i];
            ByteBuffer b = ByteBuffer.allocate(HEADER + FRAG_HEADER + f.length);
            b.put(T_DATA_REL).putInt(lastDelivered).putInt(ackBits);
            b.putInt(o.id).put((byte) i).put((byte) o.fragments.length).put(f);
            sendDatagram(b.array());
        }
        o.lastSent = System.nanoTime();
        o.attempts++;
    }

    private byte[] build(byte type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(HEADER + payload.length);
        b.put(type).putInt(lastDelivered).putInt(ackBits).put(payload);
        return b.array();
    }

    private void sendDatagram(byte[] data) {
        try {
            DatagramPacket dp = remote == null
                    ? new DatagramPacket(data, data.length)
                    : new DatagramPacket(data, data.length, remote);
            socket.send(dp);
            stats.onOut(data.length);
        } catch (IOException e) {
            closeWithReason("UDP: " + e.getMessage());
        }
    }

    static void sendAccept(DatagramSocket socket, SocketAddress target) {
        try {
            ByteBuffer b = ByteBuffer.allocate(HEADER + 4);
            b.put(T_ACCEPT).putInt(0).putInt(0).putInt(0x5EA1);
            byte[] data = b.array();
            socket.send(new DatagramPacket(data, data.length, target));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // приём
    // ------------------------------------------------------------------

    void onDatagram(byte[] data, int len) {
        if (!open || len < HEADER) return;
        lastRecv = System.nanoTime();
        stats.onIn(len);

        List<byte[]> deliver = new ArrayList<>(2);
        Packet control = null;

        ByteBuffer b = ByteBuffer.wrap(data, 0, len);
        byte type = b.get();
        int ackId = b.getInt();
        int bits = b.getInt();
        synchronized (lock) {
            processAck(ackId, bits);
            switch (type) {
                case T_DATA_REL:
                    receiveReliable(b, deliver);
                    break;
                case T_DATA_UNREL:
                    deliver.add(rest(b));
                    break;
                case T_PING:
                    control = new Packet(Packet.PING, 0, rest(b), false);
                    break;
                case T_PONG:
                    control = new Packet(Packet.PONG, 0, rest(b), false);
                    break;
                default:
                    break;
            }
        }
        for (byte[] payload : deliver) {
            Transport.Listener l = listener;
            if (l != null) l.onPacket(new Packet(Packet.DATA, 0, payload, true));
        }
        if (control != null) {
            Transport.Listener l = listener;
            if (l != null) l.onPacket(control);
        }
    }

    private void receiveReliable(ByteBuffer b, List<byte[]> deliver) {
        if (b.remaining() < FRAG_HEADER) return;
        int id = b.getInt();
        int frag = b.get() & 0xff;
        int count = b.get() & 0xff;
        byte[] chunk = rest(b);
        if (id <= lastDelivered || count == 0 || frag >= count) return;

        byte[][] parts = incoming.computeIfAbsent(id, k -> new byte[count][]);
        if (parts.length != count) return;
        parts[frag] = chunk;
        for (byte[] p : parts) if (p == null) return;

        incoming.remove(id);
        byte[] message = new byte[total(parts)];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, message, pos, p.length);
            pos += p.length;
        }
        if (id == lastDelivered + 1) {
            deliver.add(message);
            advanceAck();
            drainReady(deliver);
        } else if (id > lastDelivered + 1) {
            ready.put(id, message);
        }
    }

    private void advanceAck() {
        lastDelivered++;
        ackBits = (ackBits << 1) | 1;
    }

    private void drainReady(List<byte[]> deliver) {
        while (true) {
            byte[] message = ready.remove(lastDelivered + 1);
            if (message == null) return;
            deliver.add(message);
            advanceAck();
        }
    }

    private void processAck(int ackId, int bits) {
        if (ackId <= 0 && bits == 0) return;
        pending.entrySet().removeIf(e -> e.getKey() <= ackId);
        for (int i = 0; i < 32; i++) {
            if ((bits & (1 << i)) != 0) pending.remove(ackId - 1 - i);
        }
    }

    private static int total(byte[][] parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        return n;
    }

    private static byte[] rest(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    // ------------------------------------------------------------------

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String remote() {
        return remoteText;
    }

    @Override
    public String protocol() {
        return "UDP";
    }

    @Override
    public NetStats stats() {
        return stats;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        if (ticker != null) ticker.interrupt();
        if (remote == null) socket.close();
        Transport.Listener l = listener;
        if (l != null) l.onClosed("соединение закрыто");
    }

    void closeWithReason(String reason) {
        if (!open) return;
        open = false;
        if (ticker != null) ticker.interrupt();
        if (remote == null) socket.close();
        Runnable hook = onClose;
        if (hook != null) hook.run();
        Transport.Listener l = listener;
        if (l != null) l.onClosed(reason);
    }
}

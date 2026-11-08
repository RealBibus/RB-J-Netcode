package netcore.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public final class TcpTransport implements Transport, Runnable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicInteger seq = new AtomicInteger();
    private final NetStats stats = new NetStats();
    private final boolean compression;

    private volatile boolean open = true;
    private Thread reader;

    public TcpTransport(Socket socket, Listener listener) throws IOException {
        this(socket, listener, true);
    }

    public TcpTransport(Socket socket, Listener listener, boolean compression) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.compression = compression;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 32 * 1024));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024));
    }

    public void startReader() {
        start();
    }

    @Override
    public void start() {
        if (reader != null) return;
        reader = new Thread(this, "netcore-read");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void run() {
        String reason = "connection closed";
        try {
            while (open) {
                Packet p = FrameCodec.read(in);
                if (p == null) break;
                stats.onIn(p.payload.length + 5);
                listener.onPacket(p);
            }
        } catch (IOException e) {
            reason = e.getMessage() == null ? "network error" : e.getMessage();
        } finally {
            close();
            listener.onClosed(reason);
        }
    }

    @Override
    public void send(Packet packet) {
        if (!open) return;
        try {
            synchronized (writeLock) {
                FrameCodec.write(out, packet, compression);
            }
            stats.onOut(packet.payload.length + 5);
        } catch (IOException e) {
            close();
            listener.onClosed("send failed: " + e.getMessage());
        }
    }

    public Packet data(byte[] payload) {
        return new Packet(Packet.DATA, seq.incrementAndGet(), payload);
    }

    public NetStats stats() {
        return stats;
    }

    public String remote() {
        return socket.getRemoteSocketAddress() == null ? "?" : socket.getRemoteSocketAddress().toString();
    }

    @Override
    public String protocol() {
        return "TCP";
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

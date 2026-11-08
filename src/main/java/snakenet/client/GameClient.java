package snakenet.client;

import snakenet.net.Msg;
import snakenet.net.Protocol;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Клиент: TCP-соединение, чтение кадров в отдельном потоке, отправка из UI. */
public final class GameClient {

    public interface Listener {
        void onFrame(List<Msg> frame);

        void onClosed(String reason);
    }

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final Object writeLock = new Object();
    private final Listener listener;
    private Thread reader;
    private volatile boolean open = true;
    private volatile long latencyMs = -1;
    private long lastPingSent;

    private GameClient(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static GameClient connect(String host, int port, Listener listener) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        GameClient c = new GameClient(s, listener);
        c.reader = new Thread(c::readLoop, "snake-client-read");
        c.reader.setDaemon(true);
        c.reader.start();
        return c;
    }

    private void readLoop() {
        String reason = "Соединение закрыто";
        try {
            List<Msg> frame = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(Msg.END)) {
                    List<Msg> ready = frame;
                    frame = new ArrayList<>();
                    if (handleInternal(ready)) continue;
                    SwingUtilities.invokeLater(() -> listener.onFrame(ready));
                } else if (!line.isEmpty()) {
                    Msg m = Msg.parse(line);
                    if (m != null) frame.add(m);
                }
            }
        } catch (IOException e) {
            reason = e.getMessage() == null ? "Соединение потеряно" : e.getMessage();
        } finally {
            open = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            String r = reason;
            SwingUtilities.invokeLater(() -> listener.onClosed(r));
        }
    }

    /** Служебные кадры (пинг) не доходят до экранов. */
    private boolean handleInternal(List<Msg> frame) {
        if (frame.size() != 1) return false;
        Msg m = frame.get(0);
        if (m.is(Protocol.PONG)) {
            if (lastPingSent > 0) latencyMs = (System.nanoTime() - lastPingSent) / 1_000_000L;
            return true;
        }
        return false;
    }

    public void send(Msg m) {
        if (!open) return;
        try {
            synchronized (writeLock) {
                out.write(m.encode());
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            close();
        }
    }

    public void ping() {
        lastPingSent = System.nanoTime();
        Msg m = Msg.of(Protocol.PING).put("t", System.currentTimeMillis());
        send(m);
    }

    public long latencyMs() {
        return latencyMs;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (reader != null) reader.interrupt();
    }
}

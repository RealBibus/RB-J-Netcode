package snakenet.server;

import snakenet.net.Msg;
import snakenet.net.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Одно подключение клиента к серверу. */
public final class Session implements Runnable {

    private final int id;
    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final Object writeLock = new Object();
    private final GameServer server;

    private volatile boolean closed;
    private Thread thread;

    volatile int playerId = -1;

    Session(int id, Socket socket, GameServer server) throws IOException {
        this.id = id;
        this.socket = socket;
        this.server = server;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    void start() {
        thread = new Thread(this, "snake-client-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty() || line.equals(Msg.END)) continue;
                Msg m = Msg.parse(line);
                if (m != null) server.onMessage(this, m);
            }
        } catch (IOException ignored) {
            // соединение оборвалось — это нормальный выход
        } finally {
            close();
            server.onDisconnect(this);
        }
    }

    public void send(Msg m) {
        sendFrame(List.of(m));
    }

    public void sendFrame(List<Msg> frame) {
        if (closed) return;
        StringBuilder sb = new StringBuilder(frame.size() * 48);
        for (Msg m : frame) sb.append(m.encode()).append('\n');
        sb.append(Msg.END).append('\n');
        try {
            synchronized (writeLock) {
                out.write(sb.toString());
                out.flush();
            }
        } catch (IOException e) {
            close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public String toString() {
        return "Session#" + id + "(player=" + playerId + ")";
    }

    /** Заглушка, чтобы протокол всегда можно было ответить ошибкой. */
    void error(String text) {
        send(Msg.of(Protocol.ERR).putStr("msg", text));
    }
}

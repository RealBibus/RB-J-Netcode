package netcore.net;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public final class MatchMaker {

    public interface Listener {
        void onMatch(Match match);
    }

    public static final class Match {
        public final String firstName;
        public final String firstHost;
        public final int firstPort;
        public final String secondName;
        public final String secondHost;
        public final int secondPort;

        public Match(String firstName, String firstHost, int firstPort,
                String secondName, String secondHost, int secondPort) {
            this.firstName = firstName;
            this.firstHost = firstHost;
            this.firstPort = firstPort;
            this.secondName = secondName;
            this.secondHost = secondHost;
            this.secondPort = secondPort;
        }
    }

    private static final class Entry {
        final String name;
        final String host;
        final int port;

        Entry(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private final Map<String, Entry> waiting = new ConcurrentHashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public int waiting() {
        return waiting.size();
    }

    public String join(String name, String host, int port) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port is out of range");
        if (waiting.containsKey(name)) throw new IllegalStateException("name is already waiting");
        Entry entry = new Entry(name, host, port);
        waiting.put(name, entry);
        queue.addLast(name);
        return name;
    }

    public void leave(String name) {
        waiting.remove(name);
        queue.remove(name);
    }

    public int pollAndNotify() {
        synchronized (lock) {
            if (queue.size() < 2) return 0;
            String firstName = queue.pollFirst();
            String secondName = queue.pollFirst();
            Entry first = waiting.remove(firstName);
            Entry second = waiting.remove(secondName);
            Match match = new Match(first.name, first.host, first.port, second.name, second.host, second.port);
            for (Listener listener : listeners) {
                listener.onMatch(match);
            }
            return 1;
        }
    }

    public void clear() {
        waiting.clear();
        queue.clear();
    }

    public MatchMaker() {
    }
}

package netcore.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public final class MatchMaker {

    public interface Listener {
        void onMatch(Match match);
    }

    /** Extend pairing without changing the engine: return true when two names may be paired. */
    public interface Pairing {
        boolean canPair(String firstName, String secondName);
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
        final long joinedNanos = System.nanoTime();

        Entry(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private final Map<String, Entry> waiting = new ConcurrentHashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList();
    private final Object lock = new Object();
    private volatile boolean enabled = true;
    private volatile int capacity = 2;
    private volatile long timeoutMs = 0;
    private volatile Pairing pairing = (first, second) -> true;

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public int waiting() {
        return waiting.size();
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int capacity() {
        return capacity;
    }

    public MatchMaker withCapacity(int capacity) {
        this.capacity = Math.max(2, capacity);
        return this;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    /** Entries older than this are dropped by the next poll; {@code 0} disables the timeout. */
    public MatchMaker withTimeoutMs(int timeoutMs) {
        this.timeoutMs = Math.max(0, timeoutMs);
        return this;
    }

    public Pairing pairing() {
        return pairing;
    }

    /** Replaces the pairing policy; the engine itself has no opinion about who may meet. */
    public MatchMaker withPairing(Pairing pairing) {
        this.pairing = pairing == null ? (first, second) -> true : pairing;
        return this;
    }

    public String join(String name, String host, int port) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port is out of range");
        if (waiting.containsKey(name)) throw new IllegalStateException("name is already waiting");
        if (waiting.size() >= capacity) throw new IllegalStateException("lobby is full");
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
            if (!enabled) return 0;
            pruneExpired();
            if (queue.size() < 2) return 0;
            List<String> names = new ArrayList<>(queue);
            for (int i = 0; i < names.size(); i++) {
                for (int j = i + 1; j < names.size(); j++) {
                    String firstName = names.get(i);
                    String secondName = names.get(j);
                    Pairing policy = pairing;
                    if (!policy.canPair(firstName, secondName)) continue;
                    Entry first = waiting.remove(firstName);
                    Entry second = waiting.remove(secondName);
                    if (first == null || second == null) continue;
                    queue.remove(firstName);
                    queue.remove(secondName);
                    Match match = new Match(first.name, first.host, first.port,
                            second.name, second.host, second.port);
                    for (Listener listener : listeners) {
                        listener.onMatch(match);
                    }
                    return 1;
                }
            }
            return 0;
        }
    }

    private void pruneExpired() {
        long limitMs = timeoutMs;
        if (limitMs <= 0) return;
        long now = System.nanoTime();
        long limitNanos = limitMs * 1_000_000L;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> e : waiting.entrySet()) {
            if (now - e.getValue().joinedNanos >= limitNanos) expired.add(e.getKey());
        }
        for (String name : expired) {
            waiting.remove(name);
            queue.remove(name);
        }
    }

    public void clear() {
        waiting.clear();
        queue.clear();
    }

    public List<String> waitingNames() {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            for (String name : queue) out.add(name);
            return out;
        }
    }

    public MatchMaker() {
    }
}

package netcore.netcode;

import netcore.net.Connection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-side replication. The application registers objects and supplies a sender; the
 * netcode layer fans sync deltas out only to each object's observers.
 */
public final class NetworkServer {

    public interface Sender {
        void send(Connection connection, byte[] payload, boolean reliable);
    }

    private final Sender sender;
    private final Map<Integer, NetworkObject> objects = new LinkedHashMap<>();
    private final List<NetworkObject> order = new CopyOnWriteArrayList<>();

    public NetworkServer(Sender sender) {
        this.sender = sender;
    }

    public synchronized void spawn(NetworkObject object, Connection owner) {
        object.spawn(owner);
        objects.put(object.id(), object);
        if (!order.contains(object)) order.add(object);
    }

    public synchronized void despawn(NetworkObject object) {
        objects.remove(object.id());
        order.remove(object);
        object.despawn();
    }

    public synchronized NetworkObject object(int id) {
        return objects.get(id);
    }

    public List<NetworkObject> objects() {
        return List.copyOf(order);
    }

    public void replicate() {
        for (NetworkObject object : order) {
            Msg delta = object.drainSync();
            if (delta == null) continue;
            byte[] payload = Wire.encode(delta);
            for (Connection c : object.observers()) {
                if (c.isOpen()) sender.send(c, payload, true);
            }
        }
    }

    public void sendFull(Connection connection, NetworkObject object) {
        object.markAllDirty();
        Msg full = object.drainSync();
        if (full != null && connection != null && connection.isOpen()) {
            sender.send(connection, Wire.encode(full), true);
        }
    }

    public void sendObservers(NetworkObject object, Msg message, boolean reliable) {
        byte[] payload = Wire.encode(message);
        for (Connection c : object.observers()) {
            if (c.isOpen()) sender.send(c, payload, reliable);
        }
    }

    public void sendObservers(NetworkObject object, Msg message) {
        sendObservers(object, message, true);
    }

    public void sendTarget(NetworkObject object, Connection connection, Msg message) {
        if (connection != null && connection.isOpen()) {
            sender.send(connection, Wire.encode(message), true);
        }
    }

    public void sendExcept(NetworkObject object, Connection except, Msg message, boolean reliable) {
        byte[] payload = Wire.encode(message);
        for (Connection c : object.observers()) {
            if (c != except && c.isOpen()) sender.send(c, payload, reliable);
        }
    }

    public void addObserver(NetworkObject object, Connection connection) {
        object.addObserver(connection);
    }

    public void removeObserver(NetworkObject object, Connection connection) {
        object.removeObserver(connection);
    }

    public void setObservers(NetworkObject object, Collection<Connection> connections) {
        object.setObservers(connections);
    }
}

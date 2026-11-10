# NetCore

NetCore is a dependency-free Java networking engine, version 2.0.

It ships a custom tick scheduler, a UDP and a TCP transport with a reliability layer, payload compression, clock synchronisation, client-side prediction and reconciliation, and a voice pipeline with jitter buffer, abuse guard and an optional WebRTC switch.

The engine does not know about any specific application. A host application supplies its own bytes and world model, so the same engine can be attached to any multiplayer project.

Version: 2.0

## Modules

- time: tick configuration, tick clock, tick runner, statistics
- net: transport interface, UDP and TCP implementations, server, client, packet codec, compression, statistics, LAN discovery
- netcode: declared message types and RPCs, replicated objects, SyncVars, observers
- sync: ping and pong clock synchronisation
- predict: predictor, simulation model, input command, correction, prediction configuration
- voice: voice mode, voice configuration, voice engine, voice packet, jitter buffer, abuse guard, WebRTC support and adapter

## Requirements

Java 25 or newer.

## Build

```bat
build.bat
```

The build produces NetCore.jar from the engine sources.

## Quick start

```java
NetServer server = new NetServer(45800, new NetServer.Listener() {
    @Override
    public void onConnect(Connection connection) {
    }

    @Override
    public void onMessage(Connection connection, byte[] payload) {
        connection.send(payload, true);
    }

    @Override
    public void onDisconnect(Connection connection, String reason) {
    }
});
server.start();

NetClient client = NetClient.connect("127.0.0.1", 45800, new NetClient.Listener() {
    @Override
    public void onMessage(byte[] payload) {
    }

    @Override
    public void onClosed(String reason) {
    }
});
```

See docs/API.md for the complete reference of every class, protocol field and configuration option.

## Declared messages and replication

The engine ships no game protocol table. The host application declares each message as an RPC, and
the netcode layer uses that declaration for parsing, routing and replication:

```java
public static final Rpc<Msg> HELLO = Rpc.server("game/hello", Codecs.MSG);
public static final Rpc<Msg> SNAP = Rpc.observers("game/snapshot", Codecs.MSG);
public static final Rpc<Msg> WELCOME = Rpc.target("game/welcome", Codecs.MSG);
```

`server`, `observers` and `target` are the three directions. Replicated state is a `SyncVar` on a
`NetworkBehaviour`; a `NetworkObject` owns the behaviour and its observers:

```java
final class Ship extends NetworkBehaviour {
    final SyncVar<String> state = syncVar("state", ValueCodecs.STRING, "");
    final SyncVar<Integer> hull = syncVar("hull", ValueCodecs.INT, 100);
}

NetworkServer netcode = new NetworkServer(sender);
NetworkObject ship = new NetworkObject(1);
ship.addBehaviour(new Ship());
netcode.spawn(ship, owner);
netcode.replicate();              // sends only dirty fields to the observers
```

On the client, add the same behaviour shape to a `NetworkClient` and feed every received message
through `apply(Msg)`. See `engine/README.md` for the full API.

## Configuration

All tuning points are plain Java objects:

- TickConfig for tick rate, subtick mode, catch-up and spin behaviour
- PredictionConfig for reset threshold, history depth and interpolation delay
- NetConfig for transport, MTU, retransmission, token buckets, reconnect window
- VoiceConfig for voice rate, jitter depth, VAD and packet limits

## Voice

The engine carries the voice path: capture, send, jitter buffer, mixing, abuse guard and the optional WebRTC switch. See docs/API.md for the protocol and the WebRTC integration steps.

## Tests

```bat
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.SelfTest
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.SubtickProbe 64 8
```

# NetCore — networking engine

NetCore is a dependency-free Java networking engine: a tick scheduler, UDP/TCP transport with its
own reliability layer, clock synchronisation, client-side prediction and an engine-level voice path.
It has no knowledge of snake. The game supplies bytes and its own simulation model, so the same
engine can drive any turn-based or real-time game.

```
engine/src/main/java/netcore/
  time/      TickConfig, TickMode, TickClock, TickRunner, TickStats
  net/       NetServer, NetClient, Connection, Transport, TcpTransport, UdpPeer,
             Packet, FrameCodec, NetCodec, NetConfig, NetInfo, NetStats, LanDiscovery,
             MatchMaker
  netcode/   MsgType, Msg, Wire, Rpc, RpcRouter, Codec, Codecs, ValueCodec, ValueCodecs,
             SyncVar, NetworkBehaviour, NetworkObject, NetworkServer, NetworkClient
  sync/      ClockSync
  predict/   Predictor, SimModel, InputCommand, Correction, PredictionConfig
  voice/     VoiceMode, VoiceConfig, VoiceEngine, VoicePacket, VoiceJitterBuffer,
             VoiceGuard, WebRtcSupport, WebRtcVoiceAdapter
  snakenet/   GameServer, Room, StandaloneServer
```

Java 25 or newer. Build it with `build.bat`, which produces `NetCore.jar`. A game only needs
`NetCore.jar` on its compile and run classpath.

## 1. Ticks

```java
TickConfig cfg = TickConfig.fixed(64);
TickRunner runner = new TickRunner(cfg, (tick, lagMs) -> {
    step();
});
runner.start();
```

`TickConfig` is immutable. Static factories:

| Factory | Meaning |
|---|---|
| `TickConfig.fixed(rate)` | one simulation step per tick |
| `TickConfig.subtick(rate, steps)` | exposes `steps` input slots per tick |

Tuning methods return a new configuration:

```java
TickConfig tuned = TickConfig.subtick(64, 8)
        .withPrecise(true)          // spin the last part of the wait
        .withCatchUp(4)             // run at most 4 late ticks, then resync
        .withSpinWindow(900_000L)   // spin window in nanoseconds
        .withPriority(Thread.NORM_PRIORITY + 1);
```

Accessors: `rate()`, `mode()`, `subSteps()`, `isSubtick()`, `precise()`, `maxCatchUp()`,
`spinWindowNanos()`, `threadPriority()`, `dt()`, `tickNanos()`, `effectiveStepsPerSecond()`.
`TickConfig.MAX_RATE` is 128; rates outside `1..128` are clamped. `TickMode` is `FIXED` or
`SUBTICK`.

`TickRunner` schedules against absolute deadlines, so wall-clock drift does not accumulate. When a
thread falls behind, it catches up by at most `maxCatchUp` ticks and then re-anchors. The last
`spinWindowNanos` of every wait are spent in `Thread.onSpinWait()` when `precise` is true; this is
what makes 128 ticks/s reliable on Windows. Set `precise` to false to yield the CPU instead.

```java
runner.currentTick();    // last tick number
runner.originNanos();    // nanoTime() anchor of tick 0
runner.stats();          // TickStats
runner.setPrecise(false);
runner.stop();
```

`TickStats` reports `ticks()`, `lateTicks()`, `resyncs()`, `maxLagMs()`, `lastLagMs()` and
`actualRate()`.

`TickClock` is pure time-to-tick arithmetic and does not start a thread:

```java
TickClock clock = new TickClock(cfg, originNanos);
int tick = clock.tickAt(System.nanoTime());
double alpha = clock.alphaAt(System.nanoTime());   // fraction of the current tick
long time = clock.timeOfTick(tick + 1);
```

## 2. Transport

`NetConfig` is immutable and built through a builder. UDP is the default; TCP is a fallback or an
explicit choice.

```java
NetConfig udp = NetConfig.builder()
        .protocol(NetConfig.Protocol.UDP)
        .allowTcp(true)
        .mtu(1200)
        .connectTimeoutMs(900)
        .resendMs(90)
        .deadAfterMs(4000)
        .compression(true)
        .dataRate(400, 1024 * 1024)
        .controlRate(200, 256 * 1024)
        .voiceRate(60, 96 * 1024)
        .reconnect(true, 30_000)
        .silentLossMs(800)
        .heartbeatMs(300)
        .reconnectBackoff(100, 900)
        .maxDatagramBytes(65535)
        .build();
```

The old convenience API is still available: `NetConfig.udp(allowTcp)`, `NetConfig.tcp()`,
`NetConfig.DEFAULT`, and the `withX(...)` methods such as `withMtu`, `withCompression`,
`withRateLimit`, `withControlRate`, `withVoiceRate`, `withReconnect`, `withSilentLoss`,
`withDeadAfter`, `withHeartbeat`, `withReconnectBackoff` and `withMaxDatagram`.

Getters mirror the builder: `preferred()`, `allowTcp()`, `mtu()`, `connectTimeoutMs()`,
`resendMs()`, `deadAfterMs()`, `compression()`, `maxPacketsPerSec()`, `maxBytesPerSec()`,
`controlPacketsPerSec()`, `controlBytesPerSec()`, `voicePacketsPerSec()`, `voiceBytesPerSec()`,
`autoReconnect()`, `reconnectWindowMs()`, `silentLossMs()`, `heartbeatMs()`,
`reconnectBackoffBaseMs()`, `reconnectBackoffMaxMs()`, `maxDatagramBytes()`, `listenUdp()` and
`listenTcp()`.

### Server

```java
NetServer server = new NetServer(45800, udp, new NetServer.Listener() {
    @Override
    public void onConnect(Connection c) {
    }

    @Override
    public void onMessage(Connection c, byte[] payload) {
        c.send(payload, true);
    }

    @Override
    public void onDisconnect(Connection c, String reason) {
    }
});
server.start();
```

`NetServer` API: `port()`, `config()`, `protocols()`, `connections()`,
`broadcast(byte[] payload)`, `broadcast(byte[] payload, boolean reliable)`,
`send(Connection c, byte[] payload)`, `stop()`, `close()`.

`Connection` API: `id()`, `remote()`, `protocol()`, `attachment` (a public `Object` slot for the
application's player object), `isOpen()`, `send(byte[] payload)`, `send(byte[] payload, boolean
reliable)`, `sendVoice(byte[] payload)`, `sendPacket(byte type, byte[] payload)`, `rttMs()`,
`jitterMs()`, `lossPercent()`, `quality()`, `stats()`, `score(rtt, jitter, loss)`,
`droppedPackets()`, `droppedVoicePackets()`, `droppedDataPackets()`,
`droppedControlPackets()` and `close()`.

`send(payload)` is reliable and ordered. `send(payload, false)` is fire-and-forget and is the right
choice for snapshots. `sendVoice(payload)` uses the dedicated voice budget and is never
acknowledged.

### Client

```java
NetClient client = NetClient.connect("192.168.0.5", 45800, udp, new NetClient.Listener() {
    @Override
    public void onMessage(byte[] payload) {
    }

    @Override
    public void onClosed(String reason) {
    }

    @Override
    public void onLinkLost(String reason, long retryForMs) {
    }

    @Override
    public void onLinkRestored() {
    }
});
```

`NetClient` API: `send(byte[] payload)`, `sendUnreliable(byte[] payload)`,
`sendVoice(byte[] payload)`, `protocol()` (`"UDP"` or `"TCP"`), `host()`, `port()`, `remote()`,
`isOpen()`, `isReconnecting()`, `clock()`, `stats()`, `rttMs()`, `jitterMs()`, `lossPercent()`,
`quality()` and `close()`. The three-argument `connect(host, port, listener)` uses
`NetConfig.udp(true)`.

The client tries the preferred transport first. If UDP stays silent for `connectTimeoutMs` and TCP
is allowed, it connects over TCP instead, provided the server listens on both transports; the
application does not have to switch. Reliable messages are fragmented to the MTU, acknowledged and
retransmitted after `resendMs`.

### Matchmaking

`MatchMaker` keeps a waiting list of players that are looking for a match and pairs them:

```java
MatchMaker maker = new MatchMaker();
maker.addListener(match -> {
    // two players: firstHost/firstPort and secondHost/secondPort
});
maker.join("alpha", "192.168.0.5", 45800);
maker.join("beta", "192.168.0.6", 45800);
maker.pollAndNotify();
```

The lobby is FIFO by join order and a poll needs two entries. A poll scans the queue in join order
and pairs the first two entries the current policy allows, notifying the listener once. `join`
returns the player name, `leave(name)` removes a player, `waiting()` reports the queue depth and
`clear()` drops all entries.

Pairing is application policy, not engine policy. `withPairing((first, second) -> ...)` accepts any
two names and returns `true` when they may meet; the default accepts everyone. `withTimeoutMs(ms)`
drops entries that waited too long on the next poll (`0`, the default, keeps them forever):

```java
MatchMaker maker = new MatchMaker()
        .withCapacity(16)
        .withTimeoutMs(30_000)
        .withPairing((a, b) -> sameRegion(a, b));
```

### Packets and codecs

`Packet` is an engine-level frame with `type` (`Packet.DATA`, `Packet.PING`, `Packet.PONG`,
`Packet.VOICE`), a 24-bit sequence, a `reliable` flag and a payload. `Packet.isControl()` is true
for ping/pong/ack traffic and `Packet.isVoice()` is true for `VOICE`.

`NetCodec` is a small deflate helper:

```java
byte[] packed = NetCodec.compress(payload);     // null when not worthwhile
byte[] raw = packed == null ? payload : NetCodec.decompress(packed);
```

`NetCodec.MIN_SIZE` is 160 bytes and `NetCodec.MAX_RAW` is 1 MB. Compression applies to reliable
and unreliable UDP payloads and to TCP frames.

`NetStats` exposes `packetsIn()`, `packetsOut()`, `bytesIn()`, `bytesOut()` and `kbpsIn()` for
telemetry.

### Clock synchronisation

`ClockSync` keeps RTT, jitter, loss and a quality score from ping/pong samples:

```java
client.clock().rttMs();
client.clock().rttMsPrecise();
client.clock().jitterMs();
client.clock().lossPercent();
client.clock().quality();          // 0..100
client.clock().serverNanosNow();
client.clock().serverSecondsNow();
client.clock().offsetNanos();
client.clock().millisSincePong();
client.clock().synced();
```

`ClockSync.onPingSent(long)`, `onPong(clientSent, serverNanos, clientRecv)` and the getters above
are public for tools and tests.

### LAN discovery

`LanDiscovery.Beacon` advertises a game on UDP port 45777; `LanDiscovery.Scanner` reports nearby
games to a listener. A `Found` entry exposes `name`, `host`, `port`, `players`, `max`, `seenAt` and
`address()`.

## 3. Declared messages and replication

The engine has no protocol table. Every message is declared by the application before it is used on
the wire, so adding a message never requires an engine change.

### Message types

`MsgType.declare(name)` registers a name and returns a shared instance. Declarations are idempotent:
the client and server can both declare the same set in static initialisers. `MsgType.find(name)`
looks an existing declaration up and `hash()` returns a stable 32-bit FNV-1a id.

`Msg` is one message. `Msg.of(MsgType)` or `Msg.of(Rpc)` creates it; `Msg.parse(line)` accepts only
declared types and returns `null` for anything else.

```java
Msg m = Msg.of(declared).put("tick", 42).putStr("label", "hello");
String line = m.encode();
Msg back = Msg.parse(line);
```

Fields: `put`, `putStr`, `raw`, `str`, `num`, `numL`, `numD`, `bool`, `has`, `rawFields`, `copy`,
`encode`. `Msg.enc` / `Msg.dec` are the Base64 URL-safe string helpers.

`Wire` frames a list of messages into one transport payload. Byte `0` marks a text frame, byte `1` a
voice frame; text frames end with the `END` line. `Wire.decode`, `Wire.isVoice`, `Wire.voice` and
`Wire.voiceFrom` are the helpers.

### RPCs

`Rpc<T>` ties a name and a codec to an allowed direction:

```java
Rpc<Msg> HELLO = Rpc.server("game/hello", Codecs.MSG);       // client -> server
Rpc<Msg> SNAP = Rpc.observers("game/snapshot", Codecs.MSG);  // server -> observers
Rpc<Msg> WELCOME = Rpc.target("game/welcome", Codecs.MSG);   // server -> one connection
```

`Codecs.MSG` copies every field; use `Codecs.NONE` for a parameterless RPC. `newMsg()`, `write(T)`,
`write(Msg, T)` and `read(Msg)` apply the codec.

`RpcRouter` dispatches by declaration and enforces the direction. A server-side router may register
only `SERVER` RPCs; a client-side router only `OBSERVERS` and `TARGET`:

```java
RpcRouter router = new RpcRouter(true);
router.on(HELLO, (from, message) -> { /* ... */ });
boolean handled = router.dispatch(from, message);
```

### SyncVars and objects

`NetworkBehaviour` declares replicated fields. The field path is `behaviour.field`, which is the key
on the wire:

```java
final class RoomState extends NetworkBehaviour {
    final SyncVar<Integer> phase = syncVar("phase", ValueCodecs.INT, 0);
    final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");

    RoomState() {
        super("room");
    }
}
```

`SyncVar` API: `get`, `set` (marks dirty only on change), `markDirty`, `isDirty`, `onChange`, `path`.
`ValueCodecs` ships `STRING`, `INT`, `LONG`, `BOOL`, `FLOAT` and `DOUBLE`.

`NetworkObject(id)` owns behaviours and observers. `spawn(owner)` runs `onStartServer`; observers are
the connections that receive its sync vars and observer RPCs. `drainSync()` produces one `nc:sync`
message containing the object id (`o`) and every dirty field, or `null` when nothing changed.
`applySync(Msg)` is the receiving side; `NetworkClient.apply(Msg)` forwards sync messages to its local
object.

`NetworkServer` ties objects to a transport:

```java
NetworkServer netcode = new NetworkServer((connection, payload, reliable) ->
        connection.send(payload, reliable));

NetworkObject object = new NetworkObject(1);
object.addBehaviour(new RoomState());
netcode.spawn(object, owner);

netcode.addObserver(object, connection);
netcode.replicate();                       // per-tick delta fan-out
netcode.sendFull(connection, object);      // full state for a late joiner
netcode.sendObservers(object, message);    // observer RPC
netcode.sendTarget(object, connection, message);
```

Only observers of an object receive its state, so one process can host many independent rooms.
`NetworkClient` mirrors the behaviour shape on the client; field paths must match, the classes do not.

## 4. Prediction and reconciliation

The game describes its state through `SimModel`:

```java
SimModel<MyState> model = new SimModel<>() {
    public MyState copy(MyState s) { ... }
    public MyState step(MyState s, InputCommand in) { ... }
    public double error(MyState auth, MyState predicted) { ... }
    public MyState lerp(MyState from, MyState to, double t) { ... }
};
```

```java
Predictor<MyState> predictor = new Predictor<>(model, PredictionConfig.DEFAULT);
predictor.reset(serverState, serverTick, ackSeq);

InputCommand cmd = predictor.push(action, tick, sub);
send(cmd.seq, cmd.tick, cmd.sub, action);

Correction c = predictor.onServerState(snapTick, ackSeq, authoritativeState);
```

`Predictor` API: `push(action, tick, sub)`, `advanceTo(tick)`, `stateAt(tick)`, `predicted()`,
`authoritative()`, `authoritativeTick()`, `predictedTick()`, `lastCorrection()`, `ready()`,
`reset(state, tick, ackSeq)`, `retime(seq, applyTick)`, `corrections()`, `snaps()`,
`pendingInputs()` and `ackSeq()`.

An `InputCommand` carries `seq`, `tick`, `sub` and `action`. `Correction` carries `snapped()`,
`minor()`, `error()`, `tick()`, `ackSeq()` and `Correction.NONE`.

`PredictionConfig` controls the reset threshold and history:

```java
PredictionConfig pc = new PredictionConfig(2.2, 48, 64, 66);
PredictionConfig simple = PredictionConfig.of(2.0);
PredictionConfig tuned = pc.withHistory(96)
        .withMaxAdvance(90)
        .withInterpolationDelay(80);
```

Fields: `snapThreshold()`, `historyTicks()`, `maxAdvanceTicks()`, `interpolationDelayMs()`.
`maxAdvanceTicks` caps how far the client may simulate ahead when it has no server state;
`interpolationDelayMs` is a hint for the renderer's playback delay.

Reconciliation order:

1. The client stores its predicted state for every tick.
2. A snapshot arrives; the client compares the stored state for the snapshot tick with the
   authoritative state and computes `error`.
3. It rolls back to the server state, drops acknowledged inputs and replays the rest.
4. If `error` exceeds `snapThreshold`, it snaps to the server state and increments `snapCount()`;
   otherwise it counts a smooth correction.

## 5. Voice

Voice is part of the engine, not the game. `VoiceEngine` captures a microphone, sends 20 ms PCM
frames through the application's transport callback, receives remote frames, reorders them in an
adaptive jitter buffer and mixes them into one speaker stream.

```java
VoiceConfig cfg = new VoiceConfig()
        .withMode(VoiceMode.UDP)
        .withJitter(3, 8, true)
        .withVoiceActivity(false, 0.02f)
        .withLimits(640, 60, 96 * 1024);

VoiceEngine voice = new VoiceEngine(cfg, frame -> client.sendVoice(frame));
voice.start();
voice.setTalking(true);                       // push-to-talk pressed
voice.onVoice(playerId, sequence, pcm);       // remote frame arrived
voice.stop();
```

`VoiceConfig` has two constructors: the default 16 kHz mono 20 ms profile and the full form
`VoiceConfig(sampleRate, frameSamples, frameMs, jitterTargetFrames, jitterMaxFrames, adaptive,
voiceActivity, vadThreshold, maxPacketBytes, maxPacketsPerSec, maxBytesPerSec, mode)`. Accessors
cover the same fields. Immutable tuners: `withMode`, `withJitter`, `withVoiceActivity`,
`withLimits`.

`VoiceEngine` API: `config()`, `setSender(Sender)`, `mode()`, `backend()`, `webRtcActive()`,
`webRtcAvailable()`, `start()`, `onVoice(playerId, seq, pcm)`,
`onVoiceFrame(playerId, seq, packet)`, `setTalking(boolean)`, `talking()`, `micOk()`,
`speakerOk()`, `level()`, `speakingCount()`, `jitterQueued()`, `jitterTarget()`,
`captureFrames()`, `sentFrames()`, `mixedFrames()` and `stop()`.

`VoicePacket` defines the voice payload: `KIND_VOICE = 1`, a three-byte header
(`kind, seqHi, seqLo`) followed by little-endian signed 16-bit PCM. API:
`encode(seq, pcm)`, `decode(data, maxPacketBytes)`, `readPcm(data, offset, length)` and
`sequence(data, offset)`.

`VoiceJitterBuffer` is per remote player. `push(seq, pcm, nowNanos)` returns false for duplicates,
late frames and oversized frames. `poll(nowNanos)` returns the next frame, silence for a gap, or
null while it waits for the target depth. Statistics: `received()`, `played()`, `dropped()`,
`late()`, `duplicates()`, `reordered()`, `concealed()`, `underruns()`, `queued()` and
`targetFrames()`.

`VoiceGuard` protects the server from voice abuse. `accept(sequence, pcm, nowNanos)` enforces a
maximum packet size, strictly increasing sequence numbers, minimum spacing between frames, and
per-second packet/byte budgets. It records `accepted()`, `rejected()`, `rejectedSize()`,
`rejectedOrder()`, `rejectedInterval()`, `rejectedRate()` and `silentFrames()`. `rms(pcm)` is a
public helper for voice activity detection.

### WebRTC switch

`VoiceMode` is `UDP` or `WEBRTC`. Set it through configuration:

```java
VoiceConfig webrtc = VoiceConfig.DEFAULT.withMode(VoiceMode.WEBRTC);
```

When the mode is `WEBRTC`, `VoiceEngine.start()` looks for all three pieces:

1. `dev.onvoid.webrtc.PeerConnectionFactory` on the classpath (the `webrtc-java` core), and
2. a `ServiceLoader` provider of `netcore.voice.WebRtcVoiceAdapter`, and
3. a successful `adapter.open(config, sender)` that reports `active()`.

If any piece is missing or fails, the engine falls back to the built-in UDP path automatically.
`webRtcAvailable()` reports whether the optional stack is present, `webRtcActive()` reports whether
it is actually carrying audio, and `backend()` returns a human-readable name.

An adapter is a normal service:

```java
public final class MyWebRtcAdapter implements WebRtcVoiceAdapter {
    public void open(VoiceConfig config, Consumer<byte[]> sender) { ... }
    public void setTalking(boolean on) { ... }
    public boolean active() { return true; }
    public String description() { return "webrtc-java"; }
    public void onRemote(int playerId, int sequence, short[] pcm) { ... }
    public void close() { ... }
}
```

Register it in `META-INF/services/netcore.voice.WebRtcVoiceAdapter`. Put `webrtc-java` and its
native library in `lib/*.jar`; `build.bat` and `run.bat` add that folder to the classpath
automatically. Without those files the game still builds and runs on UDP.

The engine also exposes `WebRtcSupport.corePresent()`, `adapterPresent()`, `available()` and
`backendName(VoiceMode)` for diagnostics.

### Wire format used by the snake example

Client to server: `[VOICE][seqHi][seqLo][pcm...]`.

Server to clients: `[VOICE][pidHi][pidLo][seqHi][seqLo][pcm...]`.

The server validates every frame with `VoiceGuard` before forwarding it. Voice frames are sent
through the voice token bucket and are never retransmitted.

## 6. Subtick and timestamps

`TickConfig.subtick(rate, steps)` advertises `steps` input slots per tick. The engine itself only
carries the fraction; the game decides what it means. In snake:

- Every input message carries `n` (sequence), `t` (tick), `d` (direction), `s` (fraction 0..1) and
  `ts` (server-clock timestamp in nanoseconds).
- The server converts `ts` back to a tick and fraction using its tick origin, rejects timestamps
  outside `inputMaxBehindTicks..inputMaxAheadTicks`, rejects duplicate/old sequences, enforces an
  input rate and caps the pending queue.
- A turn requested before the tick's movement boundary changes the direction for that movement; a
  turn requested after it is queued and applied after the next movement. Movement therefore stays
  grid-aligned and a turn can never happen in the middle of a cell.
- When several inputs target the same tick, the largest fraction wins. The server reports
  `received`, `applied`, `merged`, `same-tick`, ordering errors, the last fraction and a bucket
  histogram in every `SNAP`.

`TickClock` and the game's `ServerClock` provide the timestamp arithmetic:
`serverClock.timeOfTickNanos(tick, sub, clock)` returns the server-time instant the input should
execute.

## 7. Tests and tools

### Standalone dedicated server

The game ships a headless server entry point. It starts the hub, lets clients create lobbies, and
stops with Ctrl+C. No client window is created:

```bat
java -cp "SnakeNET.jar;NetCore.jar" snakenet.server.StandaloneServer --game=snake --port=45800
java -cp "SnakeNET.jar;NetCore.jar" snakenet.server.StandaloneServer --game=chess --port=45801
```

The UI launchers are thin wrappers around it: `run-snake.bat` / `server-snake.bat` and
`run-chess.bat` / `server-chess.bat`, plus the example folders `examples/snake/` and
`examples/chess/`.

```bat
build.bat
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.SelfTest
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.SubtickProbe 64 8
```

`SelfTest` covers ticks, the tick clock, prediction and replay, compression, quality scoring,
subtick ordering and counters, voice jitter/guard, continuous rendering, tuning factories, UDP and
TCP transport, flood protection, auto-reconnect, UDP same-address reconnect, LAN discovery, declared
messages and RPC direction, sync-var replication, hub multi-lobby isolation, chess rules and search,
and several full networked rounds including bots and reconnects.

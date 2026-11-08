# NetCore — networking engine

A dependency-free module: transport, ticks, clock synchronisation and player prediction. It knows
nothing about snake — the game supplies its own bytes and its own world model.

The snake in this repository is a usage example: `game/` depends on `NetCore.jar`, while
`NetCore.jar` does not depend on `game/` at all.

```
engine/src/main/java/netcore/
  time/      TickConfig, TickMode, TickClock, TickRunner, TickStats
  net/       NetServer, NetClient, Connection, Transport, TcpTransport, UdpPeer,
             Packet, FrameCodec, NetCodec, NetConfig, NetInfo, NetStats, LanDiscovery
  sync/      ClockSync
  predict/   Predictor, SimModel, InputCommand, Correction, PredictionConfig
```

## 1. Ticks

```java
TickConfig cfg = TickConfig.fixed(64);
TickConfig sub = TickConfig.subtick(64, 4);
TickRunner runner = new TickRunner(cfg, (tick, lagMs) -> {
    // one simulation step
});
runner.start();
...
runner.stop();
```

- Rate: **1..128** (`TickConfig.MAX_RATE`). Higher values are silently clamped.
- `TickRunner` schedules against absolute deadlines, so drift does not accumulate.
- If a thread falls behind, it catches up at most `maxCatchUp` ticks and then resynchronises —
  there is no "chasing itself" spiral.
- The last ~1.5 ms before a deadline are spent spinning. This is the only way Windows reliably
  holds 128 ticks/s. Disable it with `runner.setPrecise(false)`.
- `runner.stats()` reports the real rate, lateness and resynchronisation count.

`TickConfig.subtick(rate, steps)` enables subtick mode: input carries a tick fraction `sub` (0..1),
and the server distributes the input across sub-steps in the right order. Useful when two key
presses arrive within one tick.

`TickClock` is pure "time → tick" maths: `tickAt(nanos)`, `alphaAt(nanos)`, `timeOfTick(tick)`.
The client feeds it its estimate of server time.

## 2. Transport

**UDP** by default. The engine provides reliability itself: messages are fragmented, acknowledged
and retransmitted, while state snapshots can be sent without acknowledgements — loss is harmless
because the next snapshot is newer.

```java
NetConfig udp = NetConfig.udp(true);   // UDP primary, TCP allowed as fallback
NetConfig tcp = NetConfig.tcp();       // TCP only
```

```java
NetServer server = new NetServer(45800, udp, new NetServer.Listener() {
    public void onConnect(Connection c) { }
    public void onMessage(Connection c, byte[] payload) {
        c.send(payload);                 // reliable
    }
    public void onDisconnect(Connection c, String reason) { }
});
server.start();
server.broadcast(snapshot, false);       // snapshot — no acknowledgements
```

```java
NetClient client = NetClient.connect("192.168.0.5", 45800, udp, new NetClient.Listener() {
    public void onMessage(byte[] payload) { }
    public void onClosed(String reason) { }
});
client.protocol();   // "UDP" or "TCP" — what actually connected
client.clock().rttMs();
```

- The client tries UDP first; if the server does not answer within `connectTimeoutMs` and TCP is
  allowed, it connects over TCP. The application does not notice.
- `Connection.send(payload, reliable)`: `true` means acknowledged and ordered, `false` means
  fire-and-forget (snapshots).
- The engine pings every 400 ms on its own. In addition, the server pings every connection twice a
  second and measures its RTT, exposing `Connection.rttMs()` for the game's player table.
- A repeated UDP `CONNECT` from the same address counts as a reconnect: the engine swaps the
  transport on the existing `Connection` and does not call `onConnect`/`onDisconnect`. The session
  and the player stored in `connection.attachment` survive.
- `Transport` is the interface; implementations are `UdpPeer` and `TcpTransport`.
- `LanDiscovery` provides a beacon and a scanner on the local network (UDP 45777).

### Compression

`NetCodec` deflates payloads above 160 bytes and caps decompression at 1 MB. `compress` returns
`null` when compression is not worthwhile, which callers treat as "send raw", so incompressible
data (random snapshots, already packed data) never pays a penalty. Compression is enabled with
`NetConfig.withCompression(true)` and applies to reliable and unreliable UDP payloads as well as
TCP frames.

```java
NetConfig cfg = NetConfig.udp(true).withCompression(true);
byte[] packed = NetCodec.compress(payload);
byte[] raw = packed == null ? payload : NetCodec.decompress(packed);
```

### Connection quality

Every `Connection` and every `NetClient` tracks RTT, jitter, ping loss and a 0–100 quality score:

```java
int rtt     = client.rttMs();
int jitter  = client.jitterMs();
int loss    = client.lossPercent();
int quality = client.quality();          // 100 = perfect link
```

`Connection.score(rttMs, jitterMs, lossPercent)` is public, so tools and tests can compute a score
from raw samples. `ClockSync` keeps the same statistics on the client side.

### Flood protection

Each connection owns an incoming token bucket, configured with
`NetConfig.withRateLimit(packetsPerSec, bytesPerSec)`. Traffic above the limit is dropped and
counted in `Connection.droppedPackets()`. A misbehaving or malicious peer cannot drown the server
with packets.

```java
NetConfig strict = NetConfig.udp(false).withRateLimit(64, 32 * 1024);
```

### Auto-reconnect

```java
NetConfig cfg = NetConfig.udp(true)
        .withReconnect(true, 30_000)     // retry window
        .withSilentLoss(800)             // declare loss after this much silence
        .withDeadAfter(4_000);           // server-side dead timeout
```

```java
NetClient client = NetClient.connect(host, port, cfg, new NetClient.Listener() {
    public void onMessage(byte[] payload) { }
    public void onClosed(String reason) { }

    public void onLinkLost(String reason, long retryForMs) { }
    public void onLinkRestored() { }
});
```

- `NetClient` detects transport loss and retries the same host and port for the configured window
  with backoff, preferring the previous UDP local port so the server can match the connection.
- `onLinkLost` fires once when the link goes down; `onLinkRestored` fires when it comes back.
  `isReconnecting()` stays true in between.
- On restore the application re-sends its `HELLO` with the session token; the server resumes the
  same player and snake. If the window expires, the client closes and the server drops the player.

## 3. Prediction and resets

The game describes its state and a deterministic step:

```java
SimModel<MyState> model = new SimModel<>() {
    public MyState copy(MyState s) { ... }
    public MyState step(MyState s, InputCommand in) { ... }
    public double error(MyState auth, MyState predicted) { ... }   // in game units
    public MyState lerp(MyState from, MyState to, double t) { ... }
};
```

```java
Predictor<MyState> predictor = new Predictor<>(model, PredictionConfig.of(2.0));
predictor.reset(serverState, serverTick, ackSeq);

// key press: apply locally and remember
InputCommand cmd = predictor.push(action, tick, sub);
send(cmd.seq, tick, sub, action);

// an authoritative snapshot arrived
Correction c = predictor.onServerState(snapTick, ackSeq, authoritativeState);
if (c.snapped()) {
    // error above threshold: move the player to the server state at once
} else if (c.minor()) {
    // small correction: input was replayed, can be smoothed
}
```

What happens inside:

1. The client keeps a history of its inputs and predicted states per tick.
2. A server snapshot arrives: take the prediction for the same tick and compute `error`.
3. Roll back to the server state and re-apply every input the server has not acknowledged yet
   (according to `ackSeq`).
4. If `error > snapThreshold`, the state is taken from the server immediately, the `snaps()`
   counter grows and the UI can flash. Otherwise it is a smooth correction.

`PredictionConfig(threshold, historyTicks)` sets the reset threshold and the history depth.
A typical threshold for a grid game is `1.5–2.5` cells: a one-tick desync is smoothed, a real
divergence is teleported.

## 4. How snake uses it

- Server: `Room` runs inside `TickRunner` at the configured rate (16/32/64/128). Snake speed is in
  cells per second, so the tick rate only affects responsiveness; movement accumulates in an
  integer `acc`.
- Client: `ServerClock` estimates the current server tick from snapshots and `ClockSync`;
  `Predictor<SnakePredictState>` predicts the local snake at 60 fps, and the server acknowledges
  input through the `ls` field in each snapshot.
- Snapshots are sent about 30 times per second (`snapshotEvery`), and the client interpolates
  other snakes between snapshots.
- A disconnected player is kept for a 30 second grace window outside the lobby and marked
  `reconnecting`; the token in `HELLO` restores the session.
- The HUD shows ping, tick, quality, snapshots, corrections and resets.

## 5. Tests

```bat
java -cp "SnakeNET.jar;NetCore.jar" -Djava.awt.headless=true snakenet.SelfTest
```

Coverage includes tick rate (128 and clamping), tick maths, prediction and replay, resets,
transport and clock synchronisation, compression, connection quality, flood protection,
auto-reconnect with a server restart, UDP reconnect without a second `onConnect` and without
losing `attachment`, LAN discovery, and a full networked snake round.

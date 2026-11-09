# Snake NET — API and protocol reference

This document describes the complete public surface of the engine and the wire protocol used by the
game. The engine reference is repeated here in compact form; [engine/README.md](../engine/README.md)
has the longer tutorial. Everything is Java 25 and dependency-free unless an optional WebRTC stack
is added.

Contents:

1. Build, classpath and optional libraries
2. Text message format
3. Transport and framing
4. Game protocol
5. Input, subtick and timestamps
6. Snapshots and state
7. Voice protocol and engine
8. Configuration reference
9. Reconnect and session resume
10. Rendering and prediction
11. Tools and tests

## 1. Build, classpath and optional libraries

```bat
build.bat
java -cp "SnakeNET.jar;NetCore.jar" snakenet.Main
```

`build.bat` compiles the engine first, then the game, and writes `NetCore.jar` and `SnakeNET.jar`.
The compiler and runtime are JDK 25; the scripts find the JDK through `JAVA_HOME`, `PATH` or the
usual install folders.

If a `lib` folder exists, both scripts add `lib/*.jar` to the compile and run classpath. The folder
is empty by default. It is used only for the optional WebRTC adapter and `webrtc-java` natives.

### Integration walkthrough

The engine is a plain library. A host application creates a server, creates clients, and feeds the
engine with bytes and its own world model:

```java
NetConfig cfg = NetConfig.DEFAULT;

NetServer server = new NetServer(45800, cfg, new NetServer.Listener() {
    @Override
    public void onConnect(Connection connection) {
    }

    @Override
    public void onMessage(Connection connection, byte[] payload) {
        // application protocol: parse, apply, reply
    }

    @Override
    public void onDisconnect(Connection connection, String reason) {
    }
});
server.start();

NetClient client = NetClient.connect("192.168.0.5", 45800, cfg, new NetClient.Listener() {
    @Override
    public void onMessage(byte[] payload) {
    }

    @Override
    public void onClosed(String reason) {
    }
});
```

The same objects serve ticks, prediction and voice:

```java
TickRunner runner = new TickRunner(cfg.tickConfig(), (tick, lagMs) -> {
    // one simulation step
});
runner.start();

Predictor<MyState> predictor = new Predictor<>(model, cfg.predictionConfig());
NetConfig net = cfg.netConfig(false);
VoiceEngine voice = new VoiceEngine(cfg.voiceConfig(), frame -> client.sendVoice(frame));
voice.start();
```


## 2. Text message format

A game frame is a UTF-8 text block. Each line is one message and a final `END` line terminates the
frame:

```
HELLO|name=QWxleA|color=3|token=...
IN|n=17|t=105|d=2|s=0.375|ts=918273645
END
```

* The first field is the message type.
* Remaining fields are `key=value` and are separated by `|`.
* Values written with `Msg.putStr(...)` are Base64 URL-safe without padding and are decoded with
  `Msg.str(...)`.
* Numeric values are written with `Msg.put(...)` and read with `Msg.num(...)` or `Msg.numL(...)`.
* An empty value is allowed. Unknown keys are ignored.
* The `END` line terminates a frame; it is part of the game protocol and not visible to the engine.

`Wire` wraps a frame into a single transport payload:

| Byte | Meaning |
|---|---|
| `0` | text frame, followed by UTF-8 lines and `END` |
| `1` | voice frame |

`Wire.encode(List<Msg>)`, `Wire.encode(Msg)`, `Wire.decode(byte[])`, `Wire.isVoice(byte[])`,
`Wire.voice(seq, pcm)` and `Wire.voiceFrom(pid, seq, pcm)` are the helpers.

## 3. Transport and framing

The host chooses UDP or TCP in the create screen. The default is UDP. The host's server listens on
the selected transport only. The engine also supports a UDP-first client that falls back to TCP after
`connectTimeoutMs` (900 ms by default) when a server listens on both transports; the game does not
enable that dual listener.

| Traffic | Sent with | Reliability |
|---|---|---|
| Game messages | `Connection.send(payload)` / `NetClient.send(payload)` | acknowledged, ordered, fragmented, retransmitted after `resendMs` |
| Snapshots | `NetServer.broadcast(payload, false)` / `NetClient.sendUnreliable` | fire-and-forget |
| Voice | `Connection.sendVoice` / `NetClient.sendVoice` | fire-and-forget, dedicated budget |

`NetConfig` controls MTU, retransmission, heartbeat, dead timeouts, reconnect window, backoff and
three independent token buckets: data, control (ping/pong/ack) and voice. A full list is in section
8 and in [engine/README.md](../engine/README.md).

Compression is applied above 160 bytes when it saves space. A payload that does not shrink is sent
raw, so incompressible data never pays a penalty.

### Engine classes in this project

| Class | Role | Public surface |
|---|---|---|
| `Transport` | byte-level send loop interface | `Listener` (`onPacket`, `onClosed`), `send(Packet)`, `start()`, `isOpen()`, `remote()`, `protocol()`, `stats()`, `close()` |
| `NetServer` | server: accepts transports, fans out, keeps connection state | see [engine/README.md](../engine/README.md) |
| `NetClient` | client: connects, sends, receives, reconnects | `Listener` (`onMessage`, `onClosed`, `onLinkLost`, `onLinkRestored`), `send`, `sendUnreliable`, `sendVoice`, `clock()`, `stats()`, `rttMs()`, `jitterMs()`, `lossPercent()`, `quality()`, `protocol()`, `isOpen()`, `isReconnecting()`, `remote()`, `host()`, `port()`, `close()` |
| `Connection` | one peer session with token buckets | `attachment`, `id()`, `rttMs()`, `jitterMs()`, `lossPercent()`, `quality()`, `send`, `send(payload, reliable)`, `sendVoice`, `sendPacket`, `isOpen()`, `remote()`, `protocol()`, `stats()`, `droppedPackets()`, `droppedVoicePackets()`, `droppedDataPackets()`, `droppedControlPackets()`, `score`, `close()` |
| `NetStats` | counters for one transport | `packetsIn()`, `packetsOut()`, `bytesIn()`, `bytesOut()`, `kbpsIn()` |
| `NetInfo` | local addresses and port checks | `localAddresses()`, `setPreviewIp`, `primaryIp()`, `portFree`, `freePortFrom` |
| `LanDiscovery` | beacon + scanner | `Beacon`, `Scanner`, `Found` |
| `ClockSync` | ping/pong clock bookkeeping | `onPingSent`, `onPong`, `serverNanosNow`, `serverSecondsNow`, `rttMs`, `rttMsPrecise`, `jitterMs`, `lossPercent`, `quality`, `offsetNanos`, `millisSincePong`, `synced` |

`UdpPeer` and `TcpTransport` are internal implementations of `Transport` and are not part of the
public API.

## 4. Game protocol

### Client to server

| Type | Fields | Meaning |
|---|---|---|
| `HELLO` | `name`, `color`, `token` | join; `token` is the session token for resume |
| `IN` | `n`, `t`, `d`, `s`, `ts` | input: sequence, tick, direction, fraction, server timestamp |
| `READY` | `v` | ready state in the lobby |
| `CHAT` | `text` | chat line |
| `SET` | `k`, `v` | host setting change |
| `BOT` | `op` | host bot command (`add` / `remove`) |
| `START` | — | host starts the countdown |
| `AGAIN` | — | host returns to the lobby after `OVER` |
| `KICK_PLAYER` | `p` | host kicks a player id |

`SET` keys: `size` (index into `GameConfig.SIZE`), `speed` (index into `SPEED_CELLS`), `rate`
(index into `TICK_RATES`), `subtick` (0/1), `walls` (0/1). Only the host may change settings, and
only in the lobby. Changing `rate` or `subtick` restarts the tick runner.

### Server to client

| Type | Fields | Meaning |
|---|---|---|
| `WELCOME` | `pid`, `host`, `phase`, `rate`, plus config fields | identity and current phase |
| `LOBBY` | `phase`, `host`, `tick`, plus config fields, followed by `P` lines | lobby snapshot |
| `P` | `id`, `name`, `c`, `ready`, `bot`, `host`, `alive`, `rc`, `pg` | one lobby player |
| `START` | `phase`, `n`, plus config fields | round begins, countdown length |
| `COUNT` | `n` | countdown seconds |
| `SNAP` | fields in section 6 | authoritative tick snapshot |
| `F` | `x`, `y`, `k` | food |
| `S` | `p`, `a`, `sc`, `d`, `c`, `ac`, `ls`, `pg`, `g` | one snake |
| `E` | `k`, `p`, `x`, `y` | event: `0` eat, `1` die |
| `A` | `n`, `t` | input `n` acknowledged on tick `t` |
| `OVER` | `n` | round finished, `n` results follow |
| `R` | `p`, `name`, `sc`, `place`, `c`, `a` | one result row |
| `CHAT` | `from`, `text`, `kind`, `p`, `c` | chat; `kind=0` player, `kind=1` system |
| `ERR` | `msg` | user-visible error |
| `KICK` | `msg` | connection is being closed |

`WELCOME` and `START` also carry the configuration fields written by `GameConfig.write(...)`:
`cols`, `rows`, `cps`, `rate`, `sub`, `subs`, `walls`, `max`, `udp`, `vo`, `vm`, `vj`, `vx`.
`LOBBY` carries the same settings so late joiners and spectators stay in sync.

## 5. Input, subtick and timestamps

`IN` is the only gameplay input message:

```java
app.send(Msg.of(Protocol.IN)
        .put("d", direction)                 // 0 up, 1 right, 2 down, 3 left
        .put("t", command.tick)              // requested server tick
        .put("s", String.format(Locale.ROOT, "%.3f", command.sub))
        .put("n", command.seq)               // strictly increasing per session
        .put("ts", stamp));                  // server-clock nanoseconds
```

`ServerClock.timeOfTickNanos(tick, sub, clock)` produces `ts`. The server keeps a tick origin
anchored every tick:

```
tickOriginNanos = System.nanoTime() - tick * nanosPerTick
relative       = ts - tickOriginNanos
scheduledTick  = relative / nanosPerTick
sub            = (relative % nanosPerTick) / nanosPerTick
```

Validation in `Room.readInput`:

1. The phase must be `RUNNING` and the snake alive.
2. `n` must be positive and greater than the previous accepted sequence.
3. A token bucket allows at most `inputMaxPerSec` inputs per second (minimum 4).
4. `ts` must be within `now - (inputMaxBehindTicks + 2)` ticks and
   `now + (inputMaxAheadTicks + 1)` ticks.
5. The resulting tick must be within `tick - inputMaxBehindTicks` and
   `tick + inputMaxAheadTicks`.
6. `sub` is clamped to `0..0.999`.
7. If the pending queue is full, the oldest input is removed.

Rejected inputs increment the player counter that is exported as `SNAP.ix`, so abuse is visible
rather than silent.

Turns remain grid-aligned:

* When a movement step is due in this tick, the fraction at which it happens is
  `stepAt = (UNITS - acc) / unitsPerTick`.
* An input with `sub <= stepAt` is applied before that movement and becomes `pendingDir`.
* An input with `sub > stepAt` is stored in `queuedDir` and promoted to `pendingDir` after the
  movement, so it takes effect on the next cell boundary.
* The direction never changes in the middle of a cell.

When several inputs target the same tick, the largest `sub` wins; the rest are merged and counted.

## 6. Snapshots and state

`SNAP` fields:

| Field | Type | Meaning |
|---|---|---|
| `t` | int | server tick |
| `st` | long | server `System.nanoTime()` at send time |
| `rate` | int | tick rate |
| `ms` | int | simulation units per tick (`UNITS = 1024` per cell) |
| `se` | 0/1 | subtick enabled |
| `ss` | int | configured input slots per tick |
| `si` | int | inputs received |
| `sa` | int | inputs applied as a winning direction |
| `sm` | int | inputs merged into a winning slot |
| `sx` | int | extra inputs that shared a tick |
| `so` | int | ordering errors |
| `sl` | int | last applied fraction × 1000 |
| `sb` | string | per-slot histogram, comma-separated |
| `ix` | long | inputs rejected since round start |
| `vok` | long | voice frames accepted |
| `vdr` | long | voice frames rejected |

Snake field `S`:

| Field | Meaning |
|---|---|
| `p` | player id |
| `a` | alive (1/0) |
| `sc` | score |
| `d` | direction |
| `c` | colour index |
| `ac` | accumulator inside the current cell, `0..1023` |
| `ls` | last acknowledged input sequence for this player |
| `pg` | server-measured ping in ms, `-1` for bots |
| `g` | body cells as `x:y` joined with commas, head first |

A client interpolates remote snakes between snapshots and predicts its own snake locally. The
snapshot `t` and `st` are the anchors for both.

## 7. Voice protocol and engine

### Payloads

Client to server:

```
[VOICE][seqHi][seqLo][pcm little-endian signed 16-bit ...]
```

Server to clients:

```
[VOICE][pidHi][pidLo][seqHi][seqLo][pcm ...]
```

The server validates the client frame and forwards it to every connection except the sender. Voice
is sent through `Connection.sendVoice`, which uses the voice token bucket and never acknowledges.

### Engine classes

| Class | Role |
|---|---|
| `VoiceMode` | `UDP` or `WEBRTC` |
| `VoiceConfig` | sample rate, frame size, jitter target/max, adaptive flag, VAD, packet limits, mode |
| `VoiceEngine` | capture, send, receive, jitter, mix, WebRTC switch |
| `VoicePacket` | encode/decode of the voice payload |
| `VoiceJitterBuffer` | per-speaker reorder buffer and gap concealment |
| `VoiceGuard` | server-side validation: size, order, spacing, rate, VAD | Server counters export accepted/rejected totals in the snapshot; silent frames are counted and not dropped. |
| `WebRtcSupport` | detection and backend naming |
| `WebRtcVoiceAdapter` | `ServiceLoader` contract for an optional WebRTC stack |

`VoiceEngine` is created by the game:

```java
VoiceEngine voice = new VoiceEngine(cfg.voiceConfig(), frame -> client.sendVoice(frame));
voice.start();
voice.setTalking(true);
voice.onVoice(playerId, sequence, pcm);
voice.stop();
```

`VoiceJitterBuffer.push` rejects duplicates, late frames and oversized packets, and `poll` returns
silence when a sequence gap is concealed. The target depth starts at `jitterTargetFrames` and grows
up to `jitterMaxFrames` when the link is uneven. A speaker with no frames for 400 ms is removed.

`VoiceGuard.accept` enforces:

* maximum packet bytes (`maxPacketBytes`),
* strictly increasing 16-bit sequence (wrap-aware),
* minimum spacing of half a frame,
* `maxPacketsPerSec` and `maxBytesPerSec`,
* optional voice-activity threshold (silent frames are counted and not dropped)

Every rejection reason has its own counter; the server exports accepted/rejected totals in the
snapshot.

### WebRTC switch

The engine selects WebRTC only when all of the following are true:

1. `dev.onvoid.webrtc.PeerConnectionFactory` is on the classpath.
2. A `ServiceLoader` provider of `netcore.voice.WebRtcVoiceAdapter` exists.
3. The adapter opens successfully and reports `active()`.

Otherwise `VoiceMode.WEBRTC` falls back to UDP. `VoiceEngine.backend()`, `webRtcAvailable()` and
`webRtcActive()` report what is actually running. To install an adapter:

```
lib/webrtc-java.jar
lib/webrtc-native.dll
META-INF/services/netcore.voice.WebRtcVoiceAdapter   -> your implementation
```

The build and run scripts pick up `lib/*.jar` automatically.

## 8. Configuration reference

### `GameConfig` — the game-owned tuning hub

Every timing, prediction, input, network and voice value is defined here, so the engine does not
hardcode game policy. Defaults:

| Field | Default | Meaning |
|---|---|---|
| `cols`, `rows` | 32, 22 | board size |
| `cellsPerSec` | 9 | snake speed |
| `tickRate` | 32 | simulation rate, 8..128 |
| `subtick` | true | timestamp/fraction input |
| `subSteps` | 8 | advertised input slots per tick |
| `maxPlayers` | 8 | lobby capacity |
| `walls` | true | walls kill; false wraps |
| `udp` | true | UDP transport; false selects TCP |
| `preciseSpin` | true | spin the end of each tick wait |
| `maxCatchUp` | 4 | late ticks before resync |
| `spinWindowMicros` | 900 | spin window |
| `tickPriority` | NORM+1 | tick thread priority |
| `predictionSnap` | 2.2 | snap threshold in cells |
| `predictionHistory` | 48 | retained predicted ticks |
| `maxAdvanceTicks` | 64 | client simulation lead cap |
| `interpolationDelayMs` | 66 | playback delay hint |
| `nonlinearInterp` | true | smooth-step correction easing |
| `predictionSmoothMs` | 70 | correction ease time |
| `inputMaxPerSec` | 60 | accepted inputs per second |
| `inputMaxAheadTicks` | 8 | future timestamp tolerance |
| `inputMaxBehindTicks` | 2 | past timestamp tolerance |
| `inputMaxQueue` | 48 | pending input cap |
| `mtu` | 1200 | fragmentation size |
| `heartbeatMs` | 300 | engine heartbeat |
| `serverDeadAfterMs` | 4000 | server drops silent peers |
| `clientDeadAfterMs` | 8000 | client declares silence |
| `reconnectWindowMs` | 30000 | session grace |
| `silentLossMs` | 800 | link-loss detection |
| `reconnectBackoffBaseMs` | 100 | first retry delay |
| `reconnectBackoffMaxMs` | 900 | retry cap |
| `dataPacketsPerSec` / `dataBytesPerSec` | 400 / 1 MB | data bucket |
| `controlPacketsPerSec` / `controlBytesPerSec` | 200 / 256 KB | control bucket |
| `voice` | true | voice enabled |
| `voiceMode` | 0 | 0 UDP, 1 WebRTC |
| `voiceJitterTarget` | 3 | initial jitter depth |
| `voiceJitterMax` | 8 | maximum jitter depth |
| `voiceAdaptive` | true | grow/shrink jitter |
| `voiceActivity` | false | VAD enabled |
| `voiceVad` | 0.02 | VAD RMS threshold |
| `voiceMaxPacketsPerSec` | 60 | voice bucket |
| `voiceMaxBytesPerSec` | 96 KB | voice bucket |
| `voiceMaxPacketBytes` | 640 | maximum voice payload |

Factories translate the game choices into engine objects:

```java
TickConfig tick = cfg.tickConfig();
PredictionConfig prediction = cfg.predictionConfig();
NetConfig net = cfg.netConfig(boolean server);
VoiceConfig voice = cfg.voiceConfig();
VoiceMode mode = cfg.voiceModeValue();
```

`cfg.copy()` copies every tuning field. `cfg.write(Msg)` serialises the network-visible subset;
`GameConfig.read(Msg, defaults)` clamps it on the receiving side. `unitsPerTick()`,
`cellsPerTick()`, `sizeIndex()`, `speedIndex()`, `tickIndex()`, `sizeLabel()`, `speedLabel()` and
`tickLabel()` are presentation helpers.

### `NetConfig`

Builder: `protocol`, `allowTcp`, `mtu`, `connectTimeoutMs`, `resendMs`, `deadAfterMs`,
`compression`, `dataRate`, `controlRate`, `voiceRate`, `reconnect`, `silentLossMs`, `heartbeatMs`,
`reconnectBackoff`, `maxDatagramBytes`, then `build()`.

Legacy factories and `withX` methods remain: `udp(allowTcp)`, `tcp()`, `DEFAULT`, `withMtu`,
`withConnectTimeout`, `withCompression`, `withRateLimit`, `withControlRate`, `withVoiceRate`,
`withReconnect`, `withSilentLoss`, `withDeadAfter`, `withHeartbeat`, `withReconnectBackoff`,
`withMaxDatagram`.

### `TickConfig`

`fixed(rate)`, `subtick(rate, steps)`, `withPrecise`, `withCatchUp`, `withSpinWindow`,
`withPriority`, plus getters for every field and `dt()`, `tickNanos()`,
`effectiveStepsPerSecond()`.

### `PredictionConfig`

`new PredictionConfig(snapThreshold, historyTicks, maxAdvanceTicks, interpolationDelayMs)`,
`PredictionConfig.of(threshold)`, `DEFAULT`, `withHistory`, `withMaxAdvance`,
`withInterpolationDelay`.

### `VoiceConfig`

Full constructor:
`VoiceConfig(sampleRate, frameSamples, frameMs, jitterTargetFrames, jitterMaxFrames, adaptive,
voiceActivity, vadThreshold, maxPacketBytes, maxPacketsPerSec, maxBytesPerSec, mode)`.
Tuners: `withMode`, `withJitter`, `withVoiceActivity`, `withLimits`. `DEFAULT` is 16 kHz mono,
320 samples, 20 ms.

## 9. Reconnect and session resume

The client sends a random session token in `HELLO`. The server maps the token to the player.

* A dropped player outside the lobby is marked `reconnecting` and kept for 30 seconds.
* A repeated `HELLO` with the same token resumes the player and snake, keeps the player id, and
  clears pending inputs.
* If the token is used by a different live connection, the old connection receives `KICK` and is
  closed after the new one is admitted.
* A repeated `HELLO` on a UDP connection whose address changed is matched by the token, so no
  duplicate player is created.
* After 30 seconds the player is removed and their snake dies.

`NetClient` retries the same host and port for `reconnectWindowMs`, preferring its previous local
UDP port. `onLinkLost` fires once, `onLinkRestored` fires on recovery, and the application re-sends
`HELLO` with the same token. The UI fades in after 500 ms and shows a static white icon; only the
text animates. `RECONNECTED` appears only if the interruption lasted longer than 750 ms.

## 10. Rendering and prediction

The simulation is grid-based, but the renderer is continuous:

* `SnakeState.UNITS = 1024` units per cell.
* `SnakeRenderMath.pointsAt(xs, ys, dir, acc, unitsAhead)` advances the whole body continuously and
  supports multi-cell travel. It never blends two discrete snapshots, so 16 Hz and 128 Hz render
  with the same smoothness.
* The local snake is predicted by `Predictor<SnakePredictState>` at the render tick and advanced by
  the fraction between ticks.
* Remote snakes use the newest snapshot at or before the render time and extrapolate at most three
  ticks.
* A correction jump is eased over `predictionSmoothMs` with a smooth-step curve when
  `nonlinearInterp` is true; a genuine snap is applied immediately and flashed.

Input prediction uses the same pending/queued turn rule as the server, so a locally predicted turn
and the authoritative turn land on the same cell boundary.

## 11. Tools and tests

```bat
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.SelfTest
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.SubtickProbe 64 8
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.NetBench
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.ChaosProxy 45990 127.0.0.1 45800 80 20 2
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.UiShots docs\screens
```

`SelfTest` runs 160 checks: ticks and clock maths, motion continuity, grid-aligned turns, prediction
and replay, compression, quality scoring, subtick ordering and counters, voice jitter and guard,
continuous render maths at 16 and 32 Hz, tuning factories, UDP/TCP transport, flood protection,
auto-reconnect, UDP same-address reconnect, LAN discovery, a full three-client round, bots, kicks
and reconnects.

`SubtickProbe` starts a headless server, sends four inputs with different fractions for one tick,
and prints the server counters, the bucket histogram and the winning direction. It exits non-zero
on failure.

`NetBench` measures compression and loopback throughput and prints the quality table. `ChaosProxy`
adds latency, jitter and loss to UDP. `UiShots` renders the documentation screenshots off-screen.

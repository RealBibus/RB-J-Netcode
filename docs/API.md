# Snake NET — API and protocol reference

This document describes the complete public surface of the engine and the wire protocol used by the
game. The engine reference is repeated here in compact form; [engine/README.md](../engine/README.md)
has the longer tutorial. Everything is Java 25 and dependency-free unless an optional WebRTC stack
is added.

Contents:

1. Build, classpath and optional libraries
2. Text message format
3. Transport and framing
4. Declared messages, RPCs and sync vars
5. Hub, lobbies and game protocol
6. Input, subtick and timestamps
7. Snapshots and state
8. Voice protocol and engine
9. Configuration reference
10. Snake and chess games
11. Reconnect and session resume
12. Rendering and prediction
13. Tools and tests

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
snake/hello|name=QWxleA|color=3|token=...
snake/input|n=17|t=105|d=2|s=0.375|ts=918273645
END
```

* The first field is the declared message name. The netcode layer (`MsgType`) drops any type that
  the game did not declare, so a stale peer cannot inject messages the application does not expect.
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
| Snapshots | `Room` → its own observers | fire-and-forget |
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
| `MatchMaker` | waiting list and pairing | `addListener`, `removeListener`, `join`, `leave`, `waiting`, `waitingNames`, `pollAndNotify`, `clear`, `enabled`, `setEnabled`, `capacity`, `withCapacity`, `timeoutMs`, `withTimeoutMs`, `pairing`, `withPairing` |
| `ClockSync` | ping/pong clock bookkeeping | `onPingSent`, `onPong`, `serverNanosNow`, `serverSecondsNow`, `rttMs`, `rttMsPrecise`, `jitterMs`, `lossPercent`, `quality`, `offsetNanos`, `millisSincePong`, `synced` |
| `MsgType` | declared message type | `declare(name)`, `find(name)`, `name()`, `hash()` |
| `Msg` | one wire message | `of(MsgType)`, `of(Rpc)`, `parse`, `put`, `putStr`, `raw`, `str`, `num`, `numL`, `numD`, `bool`, `has`, `rawFields`, `copy`, `encode`, `enc`, `dec` |
| `Rpc<T>` | declared channel | `server`, `observers`, `target`, `name`, `type`, `kind`, `codec`, `newMsg`, `write`, `read` |
| `RpcRouter` | direction-checked dispatch | `on`, `handles`, `dispatch`, `count`, `serverSide` |
| `SyncVar<T>` | replicated field | `path`, `get`, `set`, `markDirty`, `isDirty`, `onChange` |
| `NetworkBehaviour` | group of sync vars | `name`, `syncVar`, `syncVars`, `onStartServer`, `onStartClient`, `onStopServer`, `onStopClient` |
| `NetworkObject` | replicated object and its observers | `id`, `owner`, `addBehaviour`, `behaviour`, `addObserver`, `removeObserver`, `setObservers`, `observers`, `spawn`, `despawn`, `markAllDirty`, `drainSync`, `applySync`, `syncMessages` |
| `NetworkServer` | server replication | `spawn`, `despawn`, `object`, `objects`, `replicate`, `sendFull`, `sendObservers`, `sendTarget`, `sendExcept`, `addObserver`, `removeObserver`, `setObservers` |
| `NetworkClient` | client-side mirror | `object`, `addBehaviour`, `apply`, `reset`, `start` |

`UdpPeer` and `TcpTransport` are internal implementations of `Transport` and are not part of the
public API.

## 4. Declared messages, RPCs and sync vars

The engine contains no game protocol constants. `netcore.netcode` provides primitives, and the game
declares every message in `snakenet.net.Net`.

### Message declaration

```java
MsgType t = MsgType.declare("game/hello");   // idempotent, shared by all callers
MsgType again = MsgType.declare("game/hello"); // same instance
Msg m = Msg.of(t).put("n", 3).putStr("name", "Alex");
Msg parsed = Msg.parse(m.encode());          // null when the type was never declared
```

`MsgType.find(name)` returns a declaration or `null`; `hash()` is a stable FNV-1a id. `Msg.parse`
silently drops unknown types, which is how undeclared or removed messages are ignored instead of
failing the whole frame.

### RPC kinds

`Rpc<T>` binds a declared name to a direction and a codec:

```java
Rpc<Msg> HELLO = Rpc.server("snake/hello", Codecs.MSG);       // client executes on server
Rpc<Msg> SNAP = Rpc.observers("snake/snapshot", Codecs.MSG);  // server fans out to observers
Rpc<Msg> WELCOME = Rpc.target("snake/welcome", Codecs.MSG);   // server targets one connection
```

`Codecs.MSG` copies the message fields; `Codecs.NONE` is for RPCs without parameters. `newMsg()`,
`write(value)`, `write(message, value)` and `read(message)` apply the codec.

`RpcRouter(boolean serverSide)` rejects a registration whose direction is illegal on that side, so a
client cannot accidentally register a client-to-server RPC and a server cannot register an observer
RPC. `on(rpc, handler)`, `handles(message)` and `dispatch(from, message)` are the entry points.

### SyncVars

A `NetworkBehaviour` groups replicated fields, and each field becomes a `SyncVar<T>`:

```java
final class RoomState extends NetworkBehaviour {
    final SyncVar<Integer> phase = syncVar("phase", ValueCodecs.INT, 0);
    final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");

    RoomState() {
        super("room");
    }
}
```

The wire path is `behaviour.field` (`room.phase`, `room.fen`). `set` marks the field dirty only when
the value changes; `onChange` fires on the receiving side. `ValueCodecs` ships `STRING`, `INT`,
`LONG`, `BOOL`, `FLOAT` and `DOUBLE`.

### Objects and observers

`NetworkObject(id)` owns behaviours and a set of observers — the connections allowed to see it.
`drainSync()` returns one `nc:sync` message with `o` (object id) and one key per dirty field, or
`null` when nothing changed. Only observers receive the delta, which is what lets one server host
many independent lobbies without leaking state between them.

```java
NetworkServer netcode = new NetworkServer((connection, payload, reliable) ->
        connection.send(payload, reliable));

NetworkObject object = new NetworkObject(10_000 + roomId);
object.addBehaviour(new RoomState());
netcode.spawn(object, null);
netcode.addObserver(object, connection);

netcode.replicate();                     // per tick: dirty fields to observers
netcode.sendFull(connection, object);    // full state for a late joiner
```

`NetworkClient` holds a local `NetworkObject` with the same behaviour shape. `apply(message)` handles
`nc:sync` before the application sees the frame, so game screens can read `syncVar.get()` directly.
Field paths, not Java classes, define the contract between the two sides.

## 5. Hub, lobbies and game protocol

A server is a hub. A client connects once, receives the lobby list, and then creates or joins one
lobby. Snake and chess lobbies can live side by side on the same server, and every lobby has its own
players, settings and replicated state.

Flow: `HELLO` → `HUB` + `ROOM…` → `LOBBY_CREATE` / `LOBBY_JOIN` → `JOINED` + `WELCOME` → gameplay.
`LOBBY_LEAVE` returns to the hub without dropping the connection or the session token.

### Client to server

All of these are declared with `Rpc.server(...)` in `snakenet.net.Net`.

| Declared name | Fields | Meaning |
|---|---|---|
| `snake/hello` | `name`, `color`, `token` | identify the session; `token` allows resume across lobbies |
| `snake/lobby/list` | — | ask for the hub and the current lobby list |
| `snake/lobby/create` | `kind`, `name`, plus config fields | create a lobby on this hub; `kind` is `0` snake or `1` chess |
| `snake/lobby/join` | `id` | join an advertised lobby |
| `snake/lobby/leave` | — | leave the current lobby and return to the hub |
| `snake/input` | `n`, `t`, `d`, `s`, `ts` | input: sequence, tick, direction, fraction, server timestamp |
| `snake/ready` | `v` | ready state in the lobby |
| `snake/chat` | `text` | chat line |
| `snake/setting` | `k`, `v` | host setting change |
| `snake/bot` | `op` | host bot command (`add` / `del`) |
| `snake/start` | — | host starts the countdown |
| `snake/again` | — | host returns to the lobby after `OVER` |
| `snake/kick` | `p` | host kicks a player id |
| `chess/move` | `p`, `f`, `t`, `pr` | chess move from square `f` to square `t`; `pr` promotion piece, `0` none. Only the side to move may send, validated against the legal move list |

`SET` keys: `size` (index into `GameConfig.SIZE`), `speed` (index into `SPEED_CELLS`), `rate`
(index into `TICK_RATES`), `subtick` (0/1), `walls` (0/1), `chessSide` (0 white, 1 black, 2 random)
and `chessBot` (search depth 1..4). Only the host may change settings, and only in the lobby.
Changing `rate` or `subtick` restarts the tick runner. Chess is a separate game kind, not a setting.

### Server to client

Declared with `Rpc.target(...)` for one connection and `Rpc.observers(...)` for every member of the
lobby.

| Declared name | Fields | Meaning |
|---|---|---|
| `snake/hub` | `sid`, `server`, `rooms` | hub identity; followed by one `snake/room` per lobby |
| `snake/room` | `id`, `kind`, `name`, `players`, `max`, `phase` | one advertised lobby |
| `snake/lobby/joined` | `id`, `kind`, `name`, `host` | you are now inside this lobby |
| `snake/lobby/left` | — | you are back at the hub |
| `snake/welcome` | `pid`, `host`, `phase`, `rate`, `room`, `kind`, `cc`, plus config fields | identity and current phase |
| `snake/lobby` | `phase`, `host`, `tick`, plus config fields, followed by `snake/player` lines | lobby snapshot |
| `snake/player` | `id`, `name`, `c`, `ready`, `bot`, `host`, `alive`, `rc`, `pg` | one lobby player |
| `snake/started` | `phase`, `n`, plus config fields | round begins, countdown length |
| `snake/count` | `n` | countdown seconds |
| `snake/snapshot` | fields in section 7 | authoritative tick snapshot |
| `snake/food` | `x`, `y`, `k` | food |
| `snake/body` | `p`, `a`, `sc`, `d`, `c`, `ac`, `ls`, `pg`, `g` | one snake |
| `snake/event` | `k`, `p`, `x`, `y` | event: `0` eat, `1` die |
| `snake/ack` | `n`, `t` | input `n` acknowledged on tick `t` |
| `snake/over` | `n` | round finished, `n` results follow |
| `snake/result` | `p`, `name`, `sc`, `place`, `c`, `a` | one result row |
| `snake/chat/out` | `from`, `text`, `kind`, `p`, `c` | chat; `kind=0` player, `kind=1` system |
| `snake/error` | `msg` | user-visible error |
| `snake/kicked` | `msg` | connection is being closed |
| `chess/move/out` | `p`, `f`, `t`, `pr`, `fen` | one applied move with the resulting position |
| `chess/end` | `w`, `r` | chess finished: `w` winner player id (`-1` for a draw), `r` reason (`checkmate` / `stalemate` / `draw`) |

Replicated state is additionally carried by the netcode layer as `nc:sync` messages: `o` is the room
object id and every other key is a declared sync-var path (`room.phase`, `room.fen`, `room.turn`,
`room.winner`, `room.reason`, `room.players`, `room.host`).

`WELCOME`, `STARTED` and `LOBBY` carry the configuration fields written by `GameConfig.write(...)`:
`cols`, `rows`, `cps`, `rate`, `sub`, `subs`, `walls`, `max`, `udp`, `vo`, `vm`, `vj`, `vx`, `cg`,
`cs`, `cd`.

## 6. Input, subtick and timestamps

`snake/input` (`Net.IN`) is the only gameplay input message:

```java
app.send(Net.IN.newMsg()
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

## 7. Snapshots and state

`snake/snapshot` (`SNAP`) fields:

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

Snake field `snake/body` (`S`):

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

## 8. Voice protocol and engine

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

## 9. Configuration reference

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
| `chess` | false | room plays chess instead of snake (set from the lobby kind) |
| `chessSide` | 0 | host colour: 0 white, 1 black, 2 random |
| `chessBotDepth` | 2 | chess bot search depth, 1..4 |
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

## 10. Snake and chess games

Snake and chess are two game kinds selected on the home screen. Each has its own standalone-server
and lobby-creation path; chess is no longer a toggle inside a snake lobby.

* `kind = 0` (`Net.KIND_SNAKE`): the room runs the snake simulation described in sections 6–7. The
  host configures board size, speed, tick rate, subtick and walls.
* `kind = 1` (`Net.KIND_CHESS`): the room is a two-player chess match. The host picks a colour
  (`chessSide`) and a bot depth (`chessBotDepth`), then starts the match.

Chess flow:

* The room uses one board and always has two sides. If the host starts alone, a bot is added
  automatically; if a friend joins, they take the opposite colour.
* Authoritative state is replicated with SyncVars: `room.fen`, `room.turn`, `room.phase`,
  `room.winner` and `room.reason`. The client rebuilds the position with `Chess.fromFen` and can
  render the board before any move message arrives.
* Players move by sending `chess/move` (`from`, `to`, `promotion`). The server validates the move
  against the legal move list of the side to move and drops illegal or out-of-turn moves.
* Bots answer while it is their turn using an alpha-beta search of depth `chessBotDepth` (1..4),
  throttled so the server stays responsive.
* `chess/move/out` carries the applied move and the resulting FEN for move animation.
* The game ends on checkmate, stalemate or a draw via `chess/end` (`w` winner, `r` reason); the phase
  becomes `OVER` and the result overlay is driven by the room sync vars.

## 11. Reconnect and session resume

The client sends a random session token in `snake/hello`. The server maps the token to a session, and
the session follows the player from the hub into a lobby.

* A dropped player outside the lobby is removed from the session map; the connection is gone.
* A dropped player inside a lobby is marked `reconnecting` and kept for 30 seconds.
* A repeated `snake/hello` with the same token resumes the same session and player id, and clears
  pending inputs; if the session is in a lobby, the room re-sends `WELCOME` and the full sync state.
* If the token is used by a different live connection, the old connection receives `snake/kicked`
  and is closed after the new one is admitted.
* A repeated `HELLO` on a UDP connection whose address changed is matched by the token, so no
  duplicate player is created.
* After 30 seconds the player is removed and their snake dies.

`NetClient` retries the same host and port for `reconnectWindowMs`, preferring its previous local
UDP port. `onLinkLost` fires once, `onLinkRestored` fires on recovery, and the application re-sends
`HELLO` with the same token. The UI fades in after 500 ms and shows a static white icon; only the
text animates. `RECONNECTED` appears only if the interruption lasted longer than 750 ms.

## 12. Rendering and prediction

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

## 13. Tools and tests

```bat
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.SelfTest
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.SubtickProbe 64 8
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.NetBench
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.ChaosProxy 45990 127.0.0.1 45800 80 20 2
java -cp "SnakeNET.jar;NetCore.jar" snakenet.tools.UiShots docs\screens
```

`SelfTest` runs 238 checks: ticks and clock maths, motion continuity, grid-aligned turns, prediction
and replay, compression, quality scoring, subtick ordering and counters, voice jitter and guard,
continuous render maths at 16 and 32 Hz, tuning factories, UDP/TCP transport, flood protection,
auto-reconnect, UDP same-address reconnect, LAN discovery, matchmaking pairing policies and timeouts,
declared messages and RPC direction, sync-var replication, hub multi-lobby isolation, chess rules and
search, full three-client rounds, bots, kicks and reconnects.

`SubtickProbe` starts a headless server and hub, creates a lobby, sends four inputs with different
fractions for one tick, and prints the server counters, the bucket histogram and the winning
direction. It exits non-zero on failure.

`NetBench` measures compression and loopback throughput and prints the quality table. `ChaosProxy`
adds latency, jitter and loss to UDP. `UiShots` renders the documentation screenshots off-screen.

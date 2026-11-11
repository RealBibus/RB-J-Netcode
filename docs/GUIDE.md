# Building a multiplayer game with NetCore

This is the practical guide to the engine. It explains how the pieces fit together and how to
build your own game on top of `NetCore.jar` — without changing or even reading the engine source.
There is no API dump here; every class mentioned is used in a working example and the complete
listing lives in [API.md](API.md).

If you only want to play the two games that ship with the repository, read the
[README](../README.md) instead.

---

## Contents

1. What you need
2. The two-minute tour
3. The mental model
4. A new project in five minutes
5. Declaring your protocol
6. The server
7. The client
8. Lobbies and sessions
9. Simulation: tick or turn
10. State: snapshots or SyncVars
11. Client-side prediction
12. Voice chat
13. Reconnect and resume
14. LAN discovery and matchmaking
15. Configuration
16. Packaging and launching
17. Case study: how snake and chess are built
18. Testing and troubleshooting
19. Checklist for a new game

---

## 1. What you need

- **JDK 25 or newer.** The build and the games target Java 25.
- **`NetCore.jar`** — the engine. This is the only JAR a new game needs.
- **`SnakeNET.jar` / `ChessNET.jar`** — the two independent example games. Useful as a reference,
  not required by your game.
- A text editor or IDE. No Gradle, Maven or other dependencies are required.

Build the engine and both games once from the repository root:

```bat
build.bat
```

On Linux or macOS the same sources can be compiled with `javac`; the `.bat` files are only
convenience wrappers for Windows.

If you are starting a new project, copy `NetCore.jar` next to your sources and compile with:

```bat
javac -cp "NetCore.jar" -d out src\mygame\*.java
```

The engine is deliberately dependency-free. The optional WebRTC voice backend is the only thing
that ever needs extra JARs in a `lib` folder.

---

## 2. The two-minute tour

The engine solves the boring, error-prone part of multiplayer:

| Concern | Engine module | What you get |
|---|---|---|
| Sending bytes over the network | `netcore.net` | UDP with reliability, ordering, fragmentation, retransmission, TCP fallback |
| Keeping clients in sync | `netcore.netcode` | declared messages, RPC routing, replicated objects, SyncVars |
| Running a fixed-rate simulation | `netcore.time` | `TickRunner`, `TickClock`, subtick slots |
| Hiding latency | `netcore.predict` | input prediction, rollback, replay, smooth correction |
| Measuring the link | `netcore.sync` | RTT, jitter, loss, clock offset |
| Talking to players | `netcore.voice` | capture, jitter buffer, mixing, abuse guard |
| Finding a game | `netcore.net` | LAN beacon/scanner, matchmaking queue |

What the engine deliberately does **not** do:

- It does not know what a player, a lobby, a snake or a chess piece is.
- It does not define a game protocol. You declare every message your game uses.
- It does not choose a network model. Real-time and turn-based games use different subsets.

This is why the same `NetCore.jar` runs both examples: a real-time snake with prediction and a
turn-based chess game with authoritative legal moves. Your game is the application layer.

---

## 3. The mental model

A multiplayer game built on NetCore has four layers:

```
  your UI / rendering
          |
  your game rules and state
          |
  NetCore netcode        declared messages, SyncVars, prediction
          |
  NetCore transport      UDP/TCP, reliability, clock sync
```

The server is authoritative. Clients send **intentions** (a direction, a move, a chat line), the
server validates them, updates the world, and sends back either state or an event. A client never
invents a result that the server did not confirm.

Three decisions shape the rest of your design:

1. **Real-time or turn-based?**
   - Real-time: a fixed tick, input prediction, frequent snapshots.
   - Turn-based: no prediction, one reliable message per move, state after each move.
2. **State or events?**
   - State: "the world is now X" (snapshots or SyncVars).
   - Events: "a move happened", "someone joined", "the round ended".
   Most games use both.
3. **One room or many?**
   The transport gives you connections. Lobbies, rooms and matchmaking are application concepts.
   The examples implement a hub with many independent lobbies on one server.

If you keep the server authoritative and send only what changed, the network layer stays small.

---

## 4. A new project in five minutes

Create this structure:

```
mygame/
  NetCore.jar
  src/
    mygame/
      Main.java
      Protocol.java
      GameServerMain.java
      GameClientMain.java
```

`Protocol.java` declares the messages. Nothing else in the project needs a registry file or code
generation.

```java
package mygame;

import netcore.netcode.Codecs;
import netcore.netcode.Rpc;

public final class Protocol {
    // client -> server
    public static final Rpc<netcore.netcode.Msg> HELLO =
            Rpc.server("mygame/hello", Codecs.MSG);
    public static final Rpc<netcore.netcode.Msg> MOVE =
            Rpc.server("mygame/move", Codecs.MSG);

    // server -> one client
    public static final Rpc<netcore.netcode.Msg> WELCOME =
            Rpc.target("mygame/welcome", Codecs.MSG);

    // server -> everyone in a room
    public static final Rpc<netcore.netcode.Msg> STATE =
            Rpc.observers("mygame/state", Codecs.MSG);
}
```

Compile and run:

```bat
javac -cp "NetCore.jar" -d out src\mygame\*.java
java -cp "out;NetCore.jar" mygame.GameServerMain
java -cp "out;NetCore.jar" mygame.GameClientMain
```

The rest of this guide fills in those two classes.

---

## 5. Declaring your protocol

### Messages are text

A frame is UTF-8 text, one message per line, terminated by `END`:

```
mygame/hello|name=QWxleA|color=2
mygame/move|f=12|t=28
END
```

- The first token is the declared message name.
- The rest are `key=value` fields separated by `|`.
- `putStr` writes Base64 URL-safe text; `str` reads it back.
- `put` writes a number; `num`, `numL` and `numD` read it.
- Unknown fields are ignored. Unknown message types are dropped at parse time.

### Declaring a type

```java
MsgType.declare("mygame/hello");
MsgType.declare("mygame/move");
```

Declarations are idempotent and can happen in a static initializer on both sides. Use the same
string on the server and the client. `Rpc.server(...)`, `Rpc.target(...)` and `Rpc.observers(...)`
declare the type implicitly — you rarely call `MsgType.declare` yourself.

### Building and parsing

```java
Msg hello = Protocol.HELLO.newMsg()
        .putStr("name", "Alex")
        .put("color", 2);

String wire = hello.encode();
Msg back = Msg.parse(wire);       // null if the type was never declared
String name = back.str("name");
int color = back.num("color", 0);
```

`Wire.encode(List<Msg>)` packs one or more messages into a transport payload.
`Wire.decode(byte[])` returns the list again. `Wire.isVoice(byte[])` separates voice frames.
The `END` terminator is part of the text frame, not a separate engine message.

### Direction is enforced

`RpcRouter` refuses to register a message in the wrong direction, so a client cannot accidentally
listen for a client-to-server message. This catches protocol mistakes immediately instead of at
runtime:

```java
RpcRouter router = new RpcRouter(true);   // true = server side
router.on(Protocol.HELLO, (from, msg) -> { /* ... */ });
router.on(Protocol.MOVE,  (from, msg) -> { /* ... */ });

boolean handled = router.dispatch(connection, msg);
```

On the client, construct `new RpcRouter(false)` and register only `target` and `observers`
messages.

---

## 6. The server

### Start a transport

```java
NetConfig cfg = NetConfig.DEFAULT;

NetServer server = new NetServer(45800, cfg, new NetServer.Listener() {
    @Override
    public void onConnect(Connection connection) {
        System.out.println("joined: " + connection.remote());
    }

    @Override
    public void onMessage(Connection connection, byte[] payload) {
        for (Msg msg : Wire.decode(payload)) {
            // route a declared message
        }
    }

    @Override
    public void onDisconnect(Connection connection, String reason) {
        System.out.println("left: " + reason);
    }
});

server.start();
```

`connection.attachment` is a plain `Object` slot owned by you. Store your player/session object
there instead of maintaining a second map keyed by connection:

```java
connection.attachment = new PlayerSession(connection);
```

### Send

```java
connection.send(Wire.encode(msg));          // reliable, ordered, fragmented
connection.send(Wire.encode(snapshot), false); // fire-and-forget, right for frequent state
connection.sendVoice(voiceBytes);           // dedicated voice budget, never retransmitted
server.broadcast(payload);                  // every connection
```

Rule of thumb:

- Commands, lobby changes, chat: reliable.
- High-frequency snapshots: unreliable, newest wins.
- Voice: `sendVoice`.

The engine fragments reliable payloads to the MTU, acknowledges them, retransmits after
`resendMs`, and closes the connection after `deadAfterMs` of silence. You do not write that code.

### A frame handler

```java
private void onPayload(Connection connection, byte[] payload) {
    for (Msg msg : Wire.decode(payload)) {
        PlayerSession player = (PlayerSession) connection.attachment;
        if (msg.is(Protocol.HELLO)) {
            player.name = msg.str("name");
            connection.send(Wire.encode(
                    Protocol.WELCOME.newMsg().put("id", player.id)));
        } else if (msg.is(Protocol.MOVE)) {
            applyMove(player, msg.num("f"), msg.num("t"));
        }
    }
}
```

### Timing

For a real-time game, run the simulation on the engine ticker, not on the network thread:

```java
TickRunner runner = new TickRunner(TickConfig.subtick(64, 8), (tick, lagMs) -> {
    stepWorld(tick);
    broadcastSnapshots(tick);
});
runner.start();
```

Never sleep in the network callbacks. They must return quickly so the transport can keep up.

---

## 7. The client

```java
NetClient client = NetClient.connect("192.168.0.5", 45800, NetConfig.DEFAULT,
        new NetClient.Listener() {
    @Override
    public void onMessage(byte[] payload) {
        for (Msg msg : Wire.decode(payload)) {
            // route a declared message
        }
    }

    @Override
    public void onClosed(String reason) {
        // show the reason and return to the menu
    }

    @Override
    public void onLinkLost(String reason, long retryForMs) {
        // show "reconnecting", keep the screen open
    }

    @Override
    public void onLinkRestored() {
        // hide the banner; the session token resumes the same player
    }
});

client.send(Wire.encode(Protocol.HELLO.newMsg().putStr("name", "Alex")));
```

`NetClient` exposes the link quality directly:

```java
client.protocol();       // "UDP" or "TCP"
client.rttMs();
client.jitterMs();
client.lossPercent();
client.quality();        // 0..100
client.clock().serverNanosNow();
```

The client tries UDP first and falls back to TCP when the server listens on both and UDP stays
silent for `connectTimeoutMs`. Your game does not need a separate TCP code path.

---

## 8. Lobbies and sessions

The engine gives you a connection. It does not give you a lobby. That is intentional: a co-op
shooter, a card game and a chess server all need different rules.

The example games implement this shape, and it is a good default:

```
connect -> HELLO -> HUB + ROOM list
        -> create or join a lobby
        -> WELCOME (your id, host flag, room id, phase)
        -> lobby updates and gameplay
        -> leave  -> back to the hub, connection stays open
```

A server object owns:

- `Map<Connection, Session>` — who is connected.
- `Map<Integer, Room>` — independent lobbies.
- A session token per player, so a reconnect can find the same session again.

A room owns:

- Its players and their ready/host flags.
- Its own configuration.
- Its own game state.
- Its own tick runner or its own turn state.

Only send room messages to that room's members. The examples use `NetworkObject` observers for
this, and `broadcastRoom` for plain messages. One process can therefore host many rooms without
state leaking between them.

For a first game, start with **one room per server**. Add the hub when you actually need it.

### Session resume

Give every client a token in `HELLO`. Keep `Map<String, Session>` on the server and map the token
back to the same player id on reconnect. On `NetClient`, enable automatic reconnect:

```java
NetConfig cfg = NetConfig.builder()
        .reconnect(true, 30_000)
        .heartbeatMs(300)
        .silentLossMs(800)
        .build();
```

Then treat `onLinkLost` as a UI state, not a disconnect. When the link returns, send `HELLO`
again with the stored token. The examples resume the same lobby and player.

---

## 9. Simulation: tick or turn

### Real-time: `TickRunner`

```java
TickConfig tick = TickConfig.fixed(64);          // 64 simulation steps per second
TickRunner runner = new TickRunner(tick, (number, lagMs) -> {
    world.step();
    sendSnapshot(number);
});
runner.start();
```

For input that can arrive between ticks, use subtick slots:

```java
TickConfig tick = TickConfig.subtick(64, 8);     // 8 input slots per tick
```

Subtick is not magic interpolation. The engine carries the fraction `0..1`; your game decides
what a fraction means. Snake uses it to decide whether a turn happens before or after the cell
boundary, which keeps movement grid-aligned.

Tuning:

```java
TickConfig tuned = TickConfig.subtick(64, 8)
        .withPrecise(true)       // spin the last part of the wait; good on Windows
        .withCatchUp(4)          // at most 4 late ticks, then resync
        .withSpinWindow(900_000L);
```

`TickRunner` schedules against absolute deadlines, so a slow frame does not permanently shift the
clock. `runner.stats()` reports late ticks, resyncs and the actual rate.

`TickClock` is the pure arithmetic side, useful on the client:

```java
TickClock clock = new TickClock(tick, originNanos);
int current = clock.tickAt(System.nanoTime());
double alpha = clock.alphaAt(System.nanoTime());
long next = clock.timeOfTick(current + 1);
```

### Turn-based: no tick loop

A turn-based game needs almost none of the tick machinery. The server waits for a reliable move
message, validates it, applies it, and broadcasts the result. Chess is a complete example:

```java
if (msg.is(Net.CHESS_MOVE)) {
    if (player.color != game.turn()) return;
    Move move = game.findLegalMove(msg.num("f"), msg.num("t"), msg.num("pr", 0));
    if (move == null) return;
    game = game.apply(move);
    broadcast(Net.CHESS_MOVE_OUT.newMsg()
            .put("f", move.from).put("t", move.to)
            .put("fen", game.toFen()));
}
```

The key rule is: **never trust the client's board**. Send coordinates, look up a legal move on the
server, and reject everything else. That single check makes cheating by malformed moves impossible.

---

## 10. State: snapshots or SyncVars

### Snapshots — best for real-time

A snapshot is one message that describes the world at a tick. Send it unreliably and often. A
lost snapshot is replaced by the next one.

```java
Msg snap = Protocol.STATE.newMsg()
        .put("tick", tick)
        .put("players", players.size());

for (Player p : players) {
    snap.put("p" + p.id, p.x + "," + p.y + "," + p.score);
}
server.broadcast(Wire.encode(snap));
```

Keep snapshots small. Send positions and scores, not names, textures or rules. Send the static
parts once when a player joins.

### SyncVars — best for slow, authoritative state

A `SyncVar` is a replicated field. It marks itself dirty only when the value changes, so the
network cost is proportional to actual change.

```java
final class RoomState extends NetworkBehaviour {
    final SyncVar<Integer> phase = syncVar("phase", ValueCodecs.INT, 0);
    final SyncVar<String> fen = syncVar("fen", ValueCodecs.STRING, "");
    final SyncVar<Integer> turn = syncVar("turn", ValueCodecs.INT, 0);

    RoomState() {
        super("room");
    }
}
```

```java
NetworkServer netcode = new NetworkServer(
        (connection, payload, reliable) -> connection.send(payload, reliable));

NetworkObject object = new NetworkObject(1000 + roomId);
object.addBehaviour(new RoomState());
netcode.spawn(object, null);
netcode.addObserver(object, connection);

// once per tick
netcode.replicate();                 // sends only dirty fields to observers
netcode.sendFull(connection, object); // full state for a late joiner
```

On the client, build the same behaviour shape — the class name does not matter, the field paths
do:

```java
NetworkClient mirror = new NetworkClient();
mirror.addBehaviour(new RoomState());
mirror.apply(msg);       // consumes nc:sync messages before your game sees them
```

Then read `roomState.fen.get()` in your UI. When the server changes a value, the next
`replicate()` sends it. This is what the chess board uses: `fen`, `turn`, `phase`, `winner` and
`reason` are all SyncVars.

**Do not** mix both for the same data. Pick one owner and one transport for each fact.

---

## 11. Client-side prediction

Prediction is optional. Turn-based games never need it. Fast action games feel much better with
it.

The idea:

1. The client applies an input immediately and remembers the predicted state per tick.
2. The server confirms which input sequence it has applied.
3. The client compares its remembered state with the server snapshot.
4. If they differ, it rolls back, drops acknowledged inputs, and replays the rest.
5. A large error snaps; a small error is smoothed.

Describe your simulation once:

```java
SimModel<MyState> model = new SimModel<>() {
    public MyState copy(MyState s)       { return s.copy(); }
    public MyState step(MyState s, InputCommand in) { return s.step(in.action); }
    public double error(MyState a, MyState b) { return a.distanceTo(b); }
    public MyState lerp(MyState a, MyState b, double t) { return a.lerp(b, t); }
};
```

Then:

```java
Predictor<MyState> predictor = new Predictor<>(model, PredictionConfig.DEFAULT);
predictor.reset(serverState, serverTick, ackSeq);

InputCommand cmd = predictor.push(action, tick, sub);
sendInput(cmd);

Correction c = predictor.onServerState(snapTick, ackSeq, authoritativeState);
MyState draw = predictor.predicted();
```

`Correction.snapped()` means the error was above the threshold and the client jumped to the
server state. `Correction.minor()` means it was corrected smoothly. Count both in your
debug overlay; a rising snap rate usually means the client and server rules have diverged.

Start with `PredictionConfig.of(2.0)`, then tune history and delay only if you see correction
artefacts. Prediction amplifies rule bugs: if the client and server simulate differently, the
player will feel it immediately.

---

## 12. Voice chat

Voice is engine-level and independent of the game protocol. The engine captures 20 ms PCM frames,
sends them through your transport callback, reorders them in a jitter buffer, and mixes them into
one speaker stream.

```java
VoiceConfig voiceCfg = new VoiceConfig()
        .withMode(VoiceMode.UDP)
        .withJitter(3, 8, true)
        .withVoiceActivity(false, 0.02f)
        .withLimits(640, 60, 96 * 1024);

VoiceEngine voice = new VoiceEngine(voiceCfg, frame -> client.sendVoice(frame));
voice.start();

voice.setTalking(true);                        // push-to-talk pressed
voice.onVoice(playerId, sequence, pcm);        // a remote frame arrived
voice.stop();
```

On the server, validate every frame before forwarding:

```java
VoiceGuard guard = new VoiceGuard(voiceCfg);
if (guard.accept(sequence, pcm, System.nanoTime())) {
    forwardToRoom(player, sequence, pcm);
}
```

`VoiceGuard` enforces maximum packet size, increasing sequence numbers, minimum spacing, and
per-second packet/byte budgets. Without it, one broken client can flood a room.

To use WebRTC instead of the built-in UDP path, set `VoiceMode.WEBRTC`. The engine looks for
`webrtc-java` on the classpath and a `ServiceLoader` provider of `WebRtcVoiceAdapter`. If either
is missing, it automatically falls back to UDP. Put optional JARs in `lib/`; the launchers add
that folder to the classpath.

Voice is **never** retransmitted. A late voice frame is worse than a lost one.

---

## 13. Reconnect and resume

Reconnect has two parts, and both are required:

1. **Transport reconnect.** `NetConfig.reconnect(true, windowMs)` makes `NetClient` retry in the
   background. Listen for `onLinkLost` and `onLinkRestored`.
2. **Session resume.** The server must recognise the returning client. Send a stable token in
   `HELLO`, keep sessions by token, and reattach the new connection to the old player.

```java
// client, after HELLO was accepted once
String token = prefs.get("token");

// server
Session session = byToken.get(msg.str("token"));
if (session == null) {
    session = new Session(nextId++, randomToken(), msg.str("name"));
    byToken.put(session.token, session);
}
session.connection = connection;
connection.attachment = session;
```

Do not create a new player on every reconnect, and do not keep the old connection open in the
session map. The connection is replaceable; the session is not.

The example games also keep the room and colour across a reconnect. A reconnecting chess player
gets the same side; a reconnecting snake player keeps their score until the round ends.

---

## 14. LAN discovery and matchmaking

### LAN discovery

```java
LanDiscovery.Beacon beacon = new LanDiscovery.Beacon("My game", 45800, () -> 12, () -> 32);
beacon.start();

LanDiscovery.Scanner scanner = new LanDiscovery.Scanner(found -> {
    for (LanDiscovery.Found game : found) {
        System.out.println(game.name + " at " + game.address());
    }
});
scanner.start();
```

The beacon advertises on UDP port 45777. The scanner reports nearby games with name, address,
players and capacity. Use it for a "LAN games" list; do not use it as a replacement for a real
server browser.

### Matchmaking

```java
MatchMaker maker = new MatchMaker()
        .withCapacity(16)
        .withTimeoutMs(30_000)
        .withPairing((a, b) -> sameRegion(a, b));

maker.addListener(match -> {
    // match.firstHost / match.firstPort / match.secondHost / match.secondPort
});

maker.join("alpha", "192.168.0.5", 45800);
maker.join("beta",  "192.168.0.6", 45800);
maker.pollAndNotify();
```

Pairing policy belongs to your game. `withPairing` receives two names and returns whether they may
meet. The default accepts everyone. The queue is FIFO by join order.

---

## 15. Configuration

All tuning is done with plain immutable objects. Copy-with methods return a new instance, so a
configuration can be shared safely between threads.

### `TickConfig`

| Setting | Meaning |
|---|---|
| `fixed(rate)` | one step per tick |
| `subtick(rate, steps)` | `steps` input slots per tick |
| `withPrecise(true)` | spin the final wait window |
| `withCatchUp(n)` | catch up at most `n` late ticks |
| `withPriority(p)` | simulation thread priority |

The rate is clamped to `1..128`. `effectiveStepsPerSecond()` reports rate × substeps.

### `PredictionConfig`

| Setting | Meaning |
|---|---|
| `snapThreshold` | error above which the client snaps |
| `historyTicks` | how many predicted states to keep |
| `maxAdvanceTicks` | maximum client-ahead simulation |
| `interpolationDelayMs` | recommended render delay |

### `NetConfig`

| Setting | Meaning |
|---|---|
| `protocol(UDP/TCP)` | preferred transport |
| `allowTcp(true)` | permit TCP fallback |
| `mtu(1200)` | fragmentation unit |
| `resendMs(90)` | reliable retransmission delay |
| `deadAfterMs(4000)` | connection death timeout |
| `compression(true)` | deflate payloads above 160 bytes |
| `dataRate/controlRate/voiceRate` | three independent token buckets |
| `reconnect(true, ms)` | automatic link recovery |
| `heartbeatMs`, `silentLossMs` | link detection |
| `reconnectBackoff(base, max)` | retry pacing |

### `VoiceConfig`

Controls sample rate, frame size, jitter target and maximum, adaptive buffering, VAD threshold,
packet size and per-second budgets, and `UDP` vs `WEBRTC`.

### Your game configuration

Keep your own settings in a small immutable class and serialize them into messages. The examples
use `GameConfig` with fields for board size, speed, tick rate, subtick, walls, player limit and
chess options. When a host changes a setting, the server validates it and broadcasts the new
configuration. The client never decides the rules.

---

## 16. Packaging and launching

### Manifest

A runnable JAR needs a manifest:

```
Manifest-Version: 1.0
Main-Class: mygame.Main
Class-Path: NetCore.jar
```

Build it with the JDK tool:

```bat
jar --create --file MyGame.jar --manifest manifest.txt -C out .
```

Then run:

```bat
java -jar MyGame.jar
```

The example repository also supports a simpler layout where the launcher sets the classpath:

```bat
java -cp "MyGame.jar;NetCore.jar" mygame.Main
```

### Dedicated server

A dedicated server is just a `main` that starts the transport and waits. It needs no window and
no game assets:

```java
public static void main(String[] args) {
    GameServer server = new GameServer(45800);
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    new CountDownLatch(1).await();
}
```

Print the address the players should use, then stop with Ctrl+C. The examples do exactly this in
their own `snakenet.snake.server.StandaloneServer` and `snakenet.chess.server.StandaloneServer`:

```bat
java -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.SnakeServer --port=45800
java -cp "ChessNET.jar;NetCore.jar" snakenet.chess.ChessServer --port=45801
```

### A good launcher

A launcher should:

- find Java 25 or newer (or print a clear install link),
- fail clearly if the game JAR is missing,
- add `lib/*.jar` if the optional voice stack is present,
- pass command-line arguments through,
- keep the console open when the process exits with an error.

The `.bat` files in the repository root are working examples; copy and rename them.

---

## 17. Case study: how snake and chess are built

Both games use the same engine build and the same hub shape, but each owns its complete source
tree, protocol, UI and server entry point. There is no shared game layer.

### Snake — real-time

- **Server:** `GameServer` owns the hub. `Room` owns one match, its tick runner and its snakes.
- **Input:** `snake/input` carries direction, tick, subtick fraction and server timestamp. The
  server validates sequence, rate and timestamp range, then applies the turn at the right cell
  boundary.
- **State:** an unreliable `snake/snapshot` per tick, plus `food` and `body` lines. Events such as
  eat and die are separate messages, so the UI can react without diffing snapshots.
- **Prediction:** the client predicts its own snake and reconciles against the snapshot. Other
  snakes are interpolated from snapshots.
- **Lobbies:** one server can host many snake lobbies. Bots fill empty slots.

Controls: arrows or WASD to turn, `K` push-to-talk, `Esc` for the menu, `Tab` for the side panel.

### Chess — turn-based

- **Server:** `Room` owns the authoritative `Chess` board. It validates every move against the
  legal move list for the side to move.
- **State:** `fen`, `turn`, `phase`, `winner` and `reason` are SyncVars. A late joiner receives the
  full state with `sendFull`.
- **Events:** `chess/move/out` announces the applied move and the resulting FEN; `chess/end`
  announces the result.
- **Bot:** the same `Chess` class provides legal moves and a small search, so a single player can
  start a match against the server-side bot.
- **UI:** white is at the bottom for white, the board is flipped for black, legal targets are
  marked, and the side to move is shown in the top bar. `ChessBoardView` animates moves, fades
  captured pieces, pulses the selection and glows the king on check.

Chess and snake are separate JARs, separate protocols, separate source trees and separate server
entry points. A client always starts on the connection screen, connects to an already running
server, and only then creates or joins a lobby; neither example can start a server from the game UI.

### What to copy for your game

- Copy the **frame handler** shape: decode, dispatch, validate.
- Copy the **hub/session** shape if you need more than one room.
- Copy the **snapshot** shape for real-time movement.
- Copy the **SyncVar** shape for slowly changing authoritative state.
- Do not copy the game rules. They are the part that is yours.

---

## 18. Testing and troubleshooting

### Run the built-in checks

```bat
build.bat
java -Djava.awt.headless=true -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.SelfTest
java -Djava.awt.headless=true -cp "ChessNET.jar;NetCore.jar" snakenet.chess.SelfTest
java -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.tools.SubtickProbe 64 8
```

Each `SelfTest` covers the engine plus its own game and a full networked round. A green run is
the quickest way to know the build is healthy.

### Common problems

**Nothing happens on the network.**
Check that both sides declared the same message names. `Msg.parse` drops undeclared types
silently. Log `msg.type().name()` while bringing up a new protocol.

**The client connects but never receives state.**
Reliable messages need the server to be running. Check the server log for the connection, then
check that you actually call `netcode.replicate()` or broadcast a snapshot. State messages are
not sent automatically.

**A turn-based move does nothing.**
The server probably rejected the move. Log the legal move list and the received coordinates.
Remember that square indices depend on your board orientation; validate them on the server.

**The client snaps constantly.**
Client and server rules differ, or the client predicts inputs the server drops. Run both
simulations on the same code and compare one state per second. A rising snap count is a rule bug,
not a network bug.

**Voice does not start.**
Check microphone permissions. `VoiceEngine.micOk()` and `speakerOk()` report device state.
`webRtcAvailable()` is false unless the optional stack is installed; the engine then uses UDP.

**The game window opens but the match does not start.**
A host must start the server, connect, create a lobby, and press start. On a dedicated server,
clients join the printed address and create the lobby themselves. A headless server has no local
player and never starts a game by itself.

### Useful tools

```bat
java -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.tools.NetBench
java -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.tools.ChaosProxy 45990 127.0.0.1 45800 80 20 2
java -cp "SnakeNET.jar;NetCore.jar" snakenet.snake.tools.UiShots docs\screens\snake
java -cp "ChessNET.jar;NetCore.jar" snakenet.chess.tools.UiShots docs\screens\chess
```

`ChaosProxy` adds latency and loss, so you can test prediction and voice under bad conditions.
`NetBench` exercises the transport. `SubtickProbe` verifies input ordering at high tick rates.

---

## 19. Checklist for a new game

Connection and session:

- [ ] `NetConfig` chosen; UDP with TCP fallback is a good default.
- [ ] `HELLO` carries a name and a stable session token.
- [ ] The server maps tokens to sessions and reattaches reconnecting clients.
- [ ] `onLinkLost` shows a banner instead of closing the game.

Protocol:

- [ ] Every message is declared once, with the same name on both sides.
- [ ] Directions are enforced with `RpcRouter`.
- [ ] Reliable messages are used for commands; unreliable for frequent state.
- [ ] Unknown fields are ignored, so old and new clients can coexist.

Authority:

- [ ] The server validates every input: range, turn, cooldown, ownership.
- [ ] The client never sends its own result, score or board.
- [ ] Illegal moves are dropped without crashing the room.

State:

- [ ] Snapshots or SyncVars — one owner per fact.
- [ ] Late joiners receive full state.
- [ ] Static data is sent once, not in every snapshot.

Real-time:

- [ ] `TickRunner` drives the simulation, not the network callbacks.
- [ ] Subtick fractions have a defined meaning in the rules.
- [ ] Prediction is tested with a lag/loss proxy.

Turn-based:

- [ ] The server holds the authoritative board.
- [ ] Only the side to move may send a move.
- [ ] Move legality is checked server-side.
- [ ] The resulting state is broadcast to both players.

Operations:

- [ ] A dedicated server can run without a window.
- [ ] Java 25 is checked with a clear error message.
- [ ] The server prints its address and stops cleanly on Ctrl+C.
- [ ] `SelfTest` or your own integration test covers a full round.

If all of that is true, you have a game that is small, honest about authority, and hard to cheat
with a modified client. That is the whole point of the engine.

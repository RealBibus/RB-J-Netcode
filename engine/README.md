# NetCore — сетевой движок

Отдельный модуль без зависимостей: транспорт, тики, синхронизация часов,
предсказание игрока. Ничего не знает про змейку — игра присылает свои байты
и свою модель мира.

Змейка в этом репозитории — пример использования: `game/` зависит от `NetCore.jar`,
`NetCore.jar` от `game/` не зависит вообще.

```
engine/src/main/java/netcore/
  time/      TickConfig, TickMode, TickClock, TickRunner, TickStats
  net/       NetServer, NetClient, Connection, Transport, TcpTransport, Packet, FrameCodec,
             LanDiscovery, NetInfo
  sync/      ClockSync
  predict/   Predictor, SimModel, InputCommand, Correction, PredictionConfig
```

## 1. Тики

```java
TickConfig cfg = TickConfig.fixed(64);          // 64 тика в секунду
TickConfig sub = TickConfig.subtick(64, 4);     // 64 тика, 4 подшага на тик
TickRunner runner = new TickRunner(cfg, (tick, lagMs) -> {
    // один шаг симуляции
});
runner.start();
...
runner.stop();
```

- Частота: **1..128** (`TickConfig.MAX_RATE`). Больше — молча обрезается.
- `TickRunner` держит абсолютные дедлайны: дрейф не копится.
- Если поток опоздал, догоняет не больше `maxCatchUp` тиков, потом
  пересинхронизируется — спирали «догоняем сами себя» не будет.
- Последние ~1.5 мс до дедлайна крутится на месте (spin). Только так на Windows
  честно держится 128 тик/с. Отключается `runner.setPrecise(false)`.
- `runner.stats()` — реальная частота, опоздания, пересинхронизации.

`TickConfig.subtick(rate, steps)` — режим subtick: ввод несёт долю тика `sub` (0..1),
сервер раскладывает ввод по подшагам и применяет в правильном порядке. Полезно,
когда за один тик приходит два нажатия.

`TickClock` — чистая математика «время → тик»: `tickAt(nanos)`, `alphaAt(nanos)`,
`timeOfTick(tick)`. Клиент подставляет в него оценённое время сервера.

## 2. Транспорт

По умолчанию **UDP**. Надёжность движок делает сам: сообщения режутся на
фрагменты, подтверждаются и досылаются, а снимки состояния можно отправлять
без подтверждений — потеря не страшна, следующий снимок новее.

```java
NetConfig udp = NetConfig.udp(true);   // UDP основной, TCP разрешён как запасной
NetConfig tcp = NetConfig.tcp();       // только TCP
```

```java
NetServer server = new NetServer(45800, udp, new NetServer.Listener() {
    public void onConnect(Connection c) { }
    public void onMessage(Connection c, byte[] payload) {
        c.send(payload);                 // надёжно (с подтверждением)
    }
    public void onDisconnect(Connection c, String reason) { }
});
server.start();
server.broadcast(snapshot, false);       // снимок — без подтверждений
```

```java
NetClient client = NetClient.connect("192.168.0.5", 45800, udp, new NetClient.Listener() {
    public void onMessage(byte[] payload) { }
    public void onClosed(String reason) { }
});
client.protocol();   // "UDP" или "TCP" — чем реально подключились
client.clock().rttMs();
```

- Клиент сначала стучится по UDP; если сервер не ответил за `connectTimeoutMs`,
  а TCP разрешён — подключается по TCP. Приложение этого не замечает.
- `Connection.send(payload, reliable)`: `true` — с подтверждением и порядком,
  `false` — один раз (снимки).
- Пинг/понг движок делает сам каждые 400 мс, игре они не видны.
- `Transport` — интерфейс, реализации `UdpPeer` и `TcpTransport`.
- `LanDiscovery` — маяк и сканер в локальной сети (UDP 45777).
## 3. Предсказание и «тпха»

Игра описывает состояние и детерминированный шаг:

```java
SimModel<MyState> model = new SimModel<>() {
    public MyState copy(MyState s) { ... }
    public MyState step(MyState s, InputCommand in) { ... }
    public double error(MyState auth, MyState predicted) { ... }   // в игровых единицах
    public MyState lerp(MyState from, MyState to, double t) { ... }
};
```

```java
Predictor<MyState> predictor = new Predictor<>(model, PredictionConfig.of(2.0));
predictor.reset(serverState, serverTick, ackSeq);

// нажатие: сразу применяем локально и запоминаем
InputCommand cmd = predictor.push(action, tick, sub);
send(cmd.seq, tick, sub, action);

// пришёл авторитетный снимок
Correction c = predictor.onServerState(snapTick, ackSeq, authoritativeState);
if (c.snapped()) {
    // ошибка больше порога: мгновенно переносим игрока к серверу
} else if (c.minor()) {
    // мелкая правка: переиграли вводы, можно сгладить
}
```

Что происходит внутри:

1. Клиент хранит историю своих вводов и предсказанных состояний по тикам.
2. Приходит снимок сервера: берём предсказание на тот же тик и считаем `error`.
3. Откатываемся к серверному состоянию, заново применяем все вводы,
   которые сервер ещё не подтвердил (по `ackSeq`).
4. Если `error > snapThreshold` — это «тпха»: состояние мгновенно берётся с сервера,
   счётчик `snaps()` растёт, UI может мигнуть. Иначе — плавная правка.

`PredictionConfig(threshold, historyTicks)` настраивает порог тпхи и глубину истории.

Типичный порог для сетки: `1.5–2.5` клетки. Тогда рассинхрон на один тик
сглаживается, а реальный разъезд — телепортирует.

## 4. Как это использует змейка

- Сервер: `Room` крутится в `TickRunner` с частотой из настроек (16/32/64/128).
  Скорость змейки — в клетках в секунду, поэтому тикрейт влияет только на
  отзывчивость: движение копится в целочисленном накопителе `acc`.
- Клиент: `ServerClock` оценивает текущий тик сервера по снимку и `ClockSync`,
  `Predictor<SnakePredictState>` предсказывает свою змейку на 60 fps,
  сервер подтверждает вводы полем `ls` в снимке.
- Снимки уходят не каждый тик, а ~30 раз в секунду (`snapshotEvery`),
  клиент интерполирует чужих змеек между снимками.
- В HUD видно пинг, тик, число «тпх» и правок.

## 5. Тесты

```bat
java -cp "SnakeNET.jar;NetCore.jar" -Djava.awt.headless=true snakenet.SelfTest
```

Покрывают тикрейт (включая 128 и обрезку), математику тиков, предсказание
(переигровка, тпха), транспорт + синхронизацию часов, обнаружение по UDP,
и полный сетевой раунд змейки.

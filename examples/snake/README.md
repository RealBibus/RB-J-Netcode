# Snake example

Standalone snake game on the NetCore engine.

```
build.bat                 once, from the repository root
examples\snake\play.bat   open the snake connection screen
examples\snake\server.bat dedicated server, port 45800
```

`play.bat` opens the connection screen for snake. It does not create a server. Start
`server.bat` first on the host machine, then enter the printed address on the connection
screen and press **CONNECT**. The hub opens after connecting; create a lobby there.

`server.bat` runs a headless snake server with no local player. It never starts a match by
itself.

Game code lives in `src/main/java/snakenet/snake/`: rules (`SnakeState`), the bot
(`BotBrain`), prediction and render math, plus the `SnakeMain` and `SnakeServer` entry
points. The shared UI, client and protocol stay in the root `game/` tree.

Controls: arrows/WASD turn, K push-to-talk, Esc menu, Tab panel.

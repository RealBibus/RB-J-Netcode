# Examples

Two separate games run on the same engine build:

- `snake/` — real-time snake rules, bot, prediction and launchers
- `chess/` — turn-based chess rules, search, board view and launchers

Each folder is a complete, independent program. It has its own source tree, protocol, UI, client
entry point, dedicated server entry point, `play.bat` and `server.bat`. Nothing is shared between
the two games except the `NetCore.jar` engine library.

The build produces one JAR per game:

```
SnakeNET.jar   snakenet.snake.*    client + server
ChessNET.jar   snakenet.chess.*    client + server
NetCore.jar    netcore.*           engine library
```

Build once from the root:

```
build.bat
```

Then start either game:

```
examples\snake\play.bat
examples\snake\server.bat
examples\chess\play.bat
examples\chess\server.bat
```

The root also exposes the same launchers directly:

```
run-snake.bat   run-chess.bat
server-snake.bat server-chess.bat
```

The clients never create a server. `play.bat` only opens the connection screen; the dedicated
server is started separately with `server.bat`.

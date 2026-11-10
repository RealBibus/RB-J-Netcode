# Examples

Two separate games run on the same engine build:

- `snake/` — real-time snake
- `chess/` — turn-based chess

Each folder has its own `play.bat` and `server.bat`, so the game and its dedicated server
are launched separately. Both use the shared `SnakeNET.jar` and `NetCore.jar` from the
repository root.

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

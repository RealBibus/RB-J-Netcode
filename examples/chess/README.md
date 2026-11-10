# Chess example

Chess is a separate game on the same NetCore engine. It has its own launcher, its own
dedicated server and its own 2-player lobby. It is not a toggle inside the snake game.

```
build.bat                 once, from the repository root
examples\chess\play.bat   open Create chess match
examples\chess\server.bat dedicated server, port 45801
```

`play.bat` opens the chess host screen directly. Choose **White**, **Black** or
**Random**, choose the **bot difficulty**, then press **CREATE MATCH**. A lone host
gets a bot opponent when the match starts; a second player joins the lobby and takes the
opposite colour.

`server.bat` runs a headless chess server. Clients use **JOIN GAME** with the printed
address, then create a chess lobby. Chess lobbies hold two players.

Rules, search and FEN state live in `snakenet.game.Chess`. The server owns the board and
replicates `fen`, `turn`, `phase`, `winner` and `reason` as sync vars.

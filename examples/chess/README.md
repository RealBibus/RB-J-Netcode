# Chess example

Chess is a separate game on the same NetCore engine. It has its own source tree, launcher,
dedicated server, two-player lobby and board renderer. It is not a toggle inside the snake
game.

```
build.bat                 once, from the repository root
examples\chess\play.bat   open the chess connection screen
examples\chess\server.bat dedicated server, port 45801
```

`play.bat` opens the chess connection screen. It does not create a server. Start
`server.bat` first on the host machine, enter the printed address, then press **CONNECT**.
The hub opens after connecting; create a chess lobby there.

In the lobby choose **White**, **Black** or **Random**, choose the **bot difficulty**, then
press **CREATE MATCH**. A lone host gets a bot opponent when the match starts; a second
player joins the lobby and takes the opposite colour.

Rules, search and FEN state live in `src/main/java/snakenet/chess/Chess.java`. Drawing and
input live in `ChessBoardView.java`; the board animates moves, highlights legal
destinations, pulses the selection and glows on check. The server owns the board and
replicates `fen`, `turn`, `phase`, `winner` and `reason` as sync vars.

Controls: click one of your pieces, then click a highlighted destination square. Right click
clears the selection. The top bar shows whose turn it is and whether it is your move.

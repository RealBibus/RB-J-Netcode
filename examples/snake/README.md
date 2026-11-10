# Snake example

Standalone snake game on the NetCore engine.

```
build.bat                 once, from the repository root
examples\snake\play.bat   open Create snake game
examples\snake\server.bat dedicated server, port 45800
```

`play.bat` opens the host screen for snake directly. Press **START SERVER** to run a
server on this computer, connect to it and open a lobby. Friends use the address shown
on the screen.

`server.bat` runs a headless snake server. Clients use **JOIN GAME** with the printed
address and then create their own lobby. There is no local player on a dedicated server.

Controls: arrows/WASD turn, K push-to-talk, Esc menu, Tab panel.

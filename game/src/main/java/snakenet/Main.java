package snakenet;

import snakenet.ui.App;

import javax.swing.SwingUtilities;


public final class Main {

    public static final String VERSION = "2.0";

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        installCrashHandler();

        String screen = null;
        String game = null;
        boolean demo = false;
        boolean lobbyDemo = false;
        for (String a : args) {
            if (a.startsWith("--screen=")) screen = a.substring("--screen=".length());
            if (a.startsWith("--game=")) game = a.substring("--game=".length());
            if (a.equals("--demo")) demo = true;
            if (a.equals("--demo-lobby")) lobbyDemo = true;
        }
        final String target = screen;
        final int kind = snakenet.net.Net.parseKind(game);
        final boolean hasGame = game != null;
        final boolean runDemo = demo;
        final boolean stayInLobby = lobbyDemo;

        SwingUtilities.invokeLater(() -> {
            Prefs prefs = new Prefs();
            App app = new App(prefs);
            if (hasGame) app.setGameKind(kind);
            app.start();
            if (target != null) {
                switch (target) {
                    case "host":
                        app.window().show(app.hostScreen());
                        break;
                    case "join":
                        app.window().show(app.joinScreen());
                        break;
                    case "home":
                        app.window().show(app.homeScreen());
                        break;
                    default:
                        break;
                }
            }
            if (runDemo) {
                app.startDemo(true);
            } else if (stayInLobby) {
                app.startDemo(false);
            }
        });
    }

    
    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.nio.file.Path dir = java.nio.file.Path.of(
                        System.getProperty("user.home"), ".snakenet");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path log = dir.resolve("crash-" + System.currentTimeMillis() + ".txt");
                java.io.StringWriter sw = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(log, sw.toString());
            } catch (Throwable ignored) {
            }
            error.printStackTrace();
            javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Something went wrong:\n" + error + "\n\nDetails are in %USERPROFILE%\\.snakenet",
                    "Snake NET", javax.swing.JOptionPane.ERROR_MESSAGE));
        });
    }
}

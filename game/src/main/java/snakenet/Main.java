package snakenet;

import snakenet.ui.App;

import javax.swing.SwingUtilities;

/** Точка входа. */
public final class Main {

    public static final String VERSION = "1.0";

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        installCrashHandler();

        String screen = null;
        boolean demo = false;
        boolean lobbyDemo = false;
        for (String a : args) {
            if (a.startsWith("--screen=")) screen = a.substring("--screen=".length());
            if (a.equals("--demo")) demo = true;
            if (a.equals("--demo-lobby")) lobbyDemo = true;
        }
        final String target = screen;
        final boolean runDemo = demo;
        final boolean stayInLobby = lobbyDemo;

        SwingUtilities.invokeLater(() -> {
            Prefs prefs = new Prefs();
            App app = new App(prefs);
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

    /** Чтобы запуск без консоли не терял ошибки: пишем их в файл и показываем окно. */
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
                    "Упс, что-то сломалось:\n" + error + "\n\nПодробности в папке %USERPROFILE%\\.snakenet",
                    "Snake NET", javax.swing.JOptionPane.ERROR_MESSAGE));
        });
    }
}

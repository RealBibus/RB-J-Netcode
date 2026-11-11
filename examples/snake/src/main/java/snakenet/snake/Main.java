package snakenet.snake;

import snakenet.snake.ui.App;

import javax.swing.SwingUtilities;

public final class Main {

    public static final String VERSION = "2.0";

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        installCrashHandler();

        String host = null;
        String port = null;
        String name = null;
        for (String a : args) {
            if (a.startsWith("--host=")) host = a.substring("--host=".length());
            if (a.startsWith("--port=")) port = a.substring("--port=".length());
            if (a.startsWith("--name=")) name = a.substring("--name=".length());
        }
        final String hostValue = host;
        final String portValue = port;
        final String nameValue = name;

        SwingUtilities.invokeLater(() -> {
            Prefs prefs = new Prefs();
            if (hostValue != null && !hostValue.isBlank()) prefs.set("host", hostValue);
            if (portValue != null && !portValue.isBlank()) prefs.set("port.snake", portValue);
            if (nameValue != null && !nameValue.isBlank()) prefs.set("name", nameValue);
            App app = new App(prefs);
            app.start();
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

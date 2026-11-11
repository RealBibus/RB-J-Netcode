package snakenet.chess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;


public final class Prefs {

    private final Path file;
    private final Properties props = new Properties();

    public Prefs() {
        file = Path.of(System.getProperty("user.home"), ".snakenet", "settings.properties");
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
        } catch (IOException ignored) {
        }
        if (!props.containsKey("name")) {
            props.setProperty("name", "Player" + (int) (Math.random() * 90 + 10));
        }
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(props.getProperty(key, String.valueOf(def)));
    }

    
    public void setPreview(String key, String value) {
        props.setProperty(key, value);
    }

    public void set(String key, Object value) {
        props.setProperty(key, String.valueOf(value));
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Snake NET");
            }
        } catch (IOException ignored) {
        }
    }
}


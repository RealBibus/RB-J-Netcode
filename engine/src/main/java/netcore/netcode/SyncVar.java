package netcore.netcode;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A replicated field. The server owns the value and writes it; clients receive it through
 * {@link NetworkObject#applySync(Msg)} and can observe changes with {@link #onChange}.
 */
public final class SyncVar<T> {

    private final String path;
    private final ValueCodec<T> codec;
    private T value;
    private boolean dirty = true;
    private Consumer<T> onChange;

    SyncVar(String path, ValueCodec<T> codec, T initial) {
        this.path = path;
        this.codec = codec;
        this.value = initial;
        this.dirty = true;
    }

    public String path() {
        return path;
    }

    public T get() {
        return value;
    }

    public void set(T next) {
        if (Objects.equals(value, next)) return;
        value = next;
        dirty = true;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public SyncVar<T> onChange(Consumer<T> listener) {
        this.onChange = listener;
        return this;
    }

    String encode() {
        dirty = false;
        return codec.write(value);
    }

    @SuppressWarnings("unchecked")
    void applyRaw(String raw) {
        T next = codec.read(raw);
        if (Objects.equals(value, next)) return;
        value = next;
        if (onChange != null) onChange.accept(value);
    }

    void resetDirty() {
        dirty = false;
    }

    @Override
    public String toString() {
        return path + "=" + codec.write(value);
    }
}

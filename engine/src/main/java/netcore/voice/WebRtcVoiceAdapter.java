package netcore.voice;

import java.util.function.Consumer;

public interface WebRtcVoiceAdapter extends AutoCloseable {

    void open(VoiceConfig config, Consumer<byte[]> sender);

    void setTalking(boolean on);

    boolean active();

    String description();

    default void onRemote(int playerId, int sequence, short[] pcm) {
    }

    @Override
    void close();
}

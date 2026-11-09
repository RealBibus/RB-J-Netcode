package netcore.voice;

import java.util.ServiceLoader;

public final class WebRtcSupport {

    private static final String CORE = "dev.onvoid.webrtc.PeerConnectionFactory";

    private WebRtcSupport() {
    }

    public static boolean corePresent() {
        return present(CORE);
    }

    public static boolean adapterPresent() {
        try {
            return ServiceLoader.load(WebRtcVoiceAdapter.class).iterator().hasNext();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean available() {
        return corePresent() && adapterPresent();
    }

    public static String backendName(VoiceMode mode) {
        if (mode == VoiceMode.WEBRTC) {
            return available() ? "WebRTC" : "UDP (WebRTC unavailable)";
        }
        return "UDP";
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, WebRtcSupport.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}

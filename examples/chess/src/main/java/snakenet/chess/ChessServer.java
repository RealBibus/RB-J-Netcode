package snakenet.chess;

/**
 * Dedicated chess server entry point. Run it on the host machine; players only connect.
 */
public final class ChessServer {

    public static void main(String[] args) throws Exception {
        String port = "--port=45801";
        java.util.List<String> forwarded = new java.util.ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--port=")) port = arg;
            else forwarded.add(arg);
        }
        forwarded.add(port);
        snakenet.chess.server.StandaloneServer.main(forwarded.toArray(new String[0]));
    }
}

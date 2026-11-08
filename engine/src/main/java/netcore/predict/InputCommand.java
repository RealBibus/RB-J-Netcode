package netcore.predict;


public final class InputCommand {

    public final int seq;
    
    public int tick;
    
    public final float sub;
    
    public final int action;

    public InputCommand(int seq, int tick, float sub, int action) {
        this.seq = seq;
        this.tick = tick;
        this.sub = Math.max(0f, Math.min(1f, sub));
        this.action = action;
    }

    @Override
    public String toString() {
        return "Input#" + seq + " t=" + tick + " sub=" + String.format("%.2f", sub) + " a=" + action;
    }
}

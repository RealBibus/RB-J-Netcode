package netcore.predict;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Клиентское предсказание и сверка с сервером.
 *
 * <p>Схема классическая: клиент запоминает свои вводы с номерами, применяет их
 * локально сразу, а когда приходит авторитетный снимок — откатывается к нему,
 * применяет заново все вводы, которые сервер ещё не подтвердил, и сравнивает
 * результат с тем, что предсказывал на тот же тик.</p>
 *
 * <p>Если расхождение больше {@link PredictionConfig#snapThreshold()}, это
 * «тпха»: поза мгновенно переносится к серверной, счётчик {@link #snaps()}
 * растёт, а UI может мигнуть. Мелкие расхождения сглаживаются через
 * {@link SimModel#lerp}.</p>
 */
public final class Predictor<S> {

    private final SimModel<S> model;
    private final PredictionConfig config;
    private final Deque<InputCommand> pending = new ArrayDeque<>();
    private final Map<Integer, S> history = new HashMap<>();

    private S authoritative;
    private int authoritativeTick = -1;
    private int ackSeq;

    private S predicted;
    private int predictedTick = -1;

    private Correction lastCorrection = Correction.NONE;
    private int nextSeq = 1;
    private int snaps;
    private int corrections;

    public Predictor(SimModel<S> model, PredictionConfig config) {
        this.model = model;
        this.config = config;
    }

    public synchronized void reset(S state, int tick, int ackSeq) {
        authoritative = model.copy(state);
        authoritativeTick = tick;
        this.ackSeq = ackSeq;
        predicted = model.copy(state);
        predictedTick = tick;
        pending.clear();
        history.clear();
        history.put(tick, model.copy(state));
        lastCorrection = Correction.NONE;
    }

    public synchronized boolean ready() {
        return predicted != null;
    }

    /** Записать ввод и сразу просимулировать до его тика. */
    public synchronized InputCommand push(int action, int tick, float sub) {
        if (predicted == null) return null;
        InputCommand cmd = new InputCommand(nextSeq++, Math.max(tick, predictedTick), sub, action);
        pending.addLast(cmd);
        advanceTo(cmd.tick);
        return cmd;
    }

    /**
     * Сервер сказал, на каком тике он реально применил ввод. Правим метку,
     * чтобы переигровка шла по серверному таймлайну, а не по нашему прогнозу.
     */
    public synchronized void retime(int seq, int applyTick) {
        for (InputCommand c : pending) {
            if (c.seq != seq) continue;
            if (applyTick > c.tick) c.tick = applyTick;
            return;
        }
    }

    /** Просимулировать до тика включительно. */
    public synchronized S advanceTo(int tick) {
        if (predicted == null) return null;
        if (tick <= predictedTick) return predicted;
        if (tick - predictedTick > 512) {
            // защита от гигантского прыжка времени
            tick = predictedTick + 512;
        }
        while (predictedTick < tick) {
            predictedTick++;
            InputCommand cmd = inputFor(predictedTick);
            predicted = model.step(predicted, cmd);
            history.put(predictedTick, model.copy(predicted));
            trimHistory();
        }
        return predicted;
    }

    /** Пришёл авторитетный снимок сервера. */
    public synchronized Correction onServerState(int serverTick, int ackSeq, S state) {
        if (predicted == null) {
            reset(state, serverTick, ackSeq);
            return Correction.NONE;
        }
        // если сервер уже дальше, чем мы успели просимулировать — догоняем,
        // иначе сравнивать не с чем
        if (serverTick > predictedTick) {
            advanceTo(serverTick);
        }
        S wasPredicted = history.get(serverTick);
        double error = wasPredicted == null ? 0 : model.error(state, wasPredicted);

        S previousPredicted = predicted;
        int previousTick = predictedTick;

        authoritative = model.copy(state);
        authoritativeTick = serverTick;
        this.ackSeq = ackSeq;

        pending.removeIf(c -> c.seq <= ackSeq);

        predicted = model.copy(state);
        predictedTick = serverTick;
        history.clear();
        history.put(serverTick, model.copy(state));

        int target = Math.max(previousTick, serverTick);
        advanceTo(target);

        boolean snap = error > config.snapThreshold();
        if (snap) {
            snaps++;
            predicted = model.copy(state);
            predictedTick = serverTick;
            history.clear();
            history.put(serverTick, model.copy(state));
            advanceTo(target);
        } else if (error > 0) {
            corrections++;
        }
        lastCorrection = new Correction(snap, error, pending.size(), serverTick, ackSeq);
        return lastCorrection;
    }

    private InputCommand inputFor(int tick) {
        InputCommand best = null;
        for (InputCommand c : pending) {
            if (c.tick > tick) continue;
            if (best == null
                    || c.tick > best.tick
                    || (c.tick == best.tick && c.sub > best.sub)
                    || (c.tick == best.tick && c.sub == best.sub && c.seq > best.seq)) {
                best = c;
            }
        }
        return best;
    }

    private void trimHistory() {
        int minTick = predictedTick - config.historyTicks();
        Iterator<Map.Entry<Integer, S>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey() < minTick) it.remove();
        }
    }

    public synchronized S predicted() {
        return predicted;
    }

    /** Предсказанное состояние на конкретном тике (для интерполяции рендера). */
    public synchronized S stateAt(int tick) {
        return history.get(tick);
    }

    public synchronized int predictedTick() {
        return predictedTick;
    }

    public synchronized int authoritativeTick() {
        return authoritativeTick;
    }

    public synchronized S authoritative() {
        return authoritative;
    }

    public synchronized int ackSeq() {
        return ackSeq;
    }

    public synchronized int pendingInputs() {
        return pending.size();
    }

    public synchronized Correction lastCorrection() {
        return lastCorrection;
    }

    public synchronized int snaps() {
        return snaps;
    }

    public synchronized int corrections() {
        return corrections;
    }
}

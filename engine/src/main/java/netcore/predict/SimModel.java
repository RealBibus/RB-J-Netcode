package netcore.predict;

/**
 * Детерминированная модель, которой пользуется предсказание.
 * Игра описывает своё состояние этим интерфейсом.
 *
 * @param <S> состояние (снимок)
 */
public interface SimModel<S> {

    S copy(S state);

    /** Один тик вперёд. {@code input} может быть {@code null}. */
    S step(S state, InputCommand input);

    /** Насколько предсказание разошлось с сервером (в игровых единицах). */
    double error(S authoritative, S predicted);

    /** Плавное смешивание для мелких правок. */
    S lerp(S from, S to, double t);
}

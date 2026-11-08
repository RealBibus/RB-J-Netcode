package netcore;

/**
 * Сетевой движок NetCore.
 *
 * <p>Модуль не знает ничего про игру: он отвечает за транспорт, тики, синхронизацию
 * времени и предсказание. Игра (например, змейка) только подписывается на события
 * и присылает свои байты.</p>
 *
 * <p>Слои:</p>
 * <ul>
 *   <li>{@code netcore.time} — тикрейт 1..128, режим {@code SUBTICK}, точный цикл тиков;</li>
 *   <li>{@code netcore.net} — TCP-транспорт с кадрами, сервер/клиент, автообнаружение;</li>
 *   <li>{@code netcore.sync} — оценка RTT и смещения часов сервера;</li>
 *   <li>{@code netcore.predict} — клиентское предсказание и сверка с сервером.</li>
 * </ul>
 */
public final class NetCore {

    public static final String VERSION = "1.0";

    private NetCore() {
    }
}

package netcore.netcode;

import java.util.Locale;

/** Built-in {@link ValueCodec}s for sync variables. */
public final class ValueCodecs {

    public static final ValueCodec<String> STRING = new ValueCodec<>() {
        @Override
        public String write(String value) {
            return Msg.enc(value);
        }

        @Override
        public String read(String raw) {
            return Msg.dec(raw);
        }
    };

    public static final ValueCodec<Integer> INT = new ValueCodec<>() {
        @Override
        public String write(Integer value) {
            return String.valueOf(value == null ? 0 : value);
        }

        @Override
        public Integer read(String raw) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    };

    public static final ValueCodec<Long> LONG = new ValueCodec<>() {
        @Override
        public String write(Long value) {
            return String.valueOf(value == null ? 0L : value);
        }

        @Override
        public Long read(String raw) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    };

    public static final ValueCodec<Boolean> BOOL = new ValueCodec<>() {
        @Override
        public String write(Boolean value) {
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }

        @Override
        public Boolean read(String raw) {
            return "1".equals(raw) || "true".equals(raw);
        }
    };

    public static final ValueCodec<Float> FLOAT = new ValueCodec<>() {
        @Override
        public String write(Float value) {
            return String.format(Locale.ROOT, "%.5f", value == null ? 0f : value);
        }

        @Override
        public Float read(String raw) {
            try {
                return Float.parseFloat(raw);
            } catch (NumberFormatException e) {
                return 0f;
            }
        }
    };

    public static final ValueCodec<Double> DOUBLE = new ValueCodec<>() {
        @Override
        public String write(Double value) {
            return String.format(Locale.ROOT, "%.6f", value == null ? 0.0 : value);
        }

        @Override
        public Double read(String raw) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
    };

    private ValueCodecs() {
    }
}

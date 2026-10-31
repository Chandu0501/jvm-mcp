package dev.jvmmcp.server.tool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

/** Type-safe argument extraction utilities for MCP tool invocations. */
public final class ToolArgs {

    private ToolArgs() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    public static String getStringArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key)) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return null;
        }
        Object val = args.get(key);

        if (val == null) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return null;
        }

        if (!(val instanceof String s)) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a string");
        }

        if (required && s.isBlank()) {
            throw new IllegalArgumentException("Argument '" + key + "' cannot be blank");
        }

        return s;
    }

    public static long getLongArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key) || (!required && args.get(key) == null)) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return 0L;
        }
        Object val = args.get(key);
        if (val instanceof Number n) {
            try {
                return decimalValue(n).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Argument '" + key + "' must be a whole number in the long range", e);
            }
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Argument '" + key + "' must be a number, got: " + s);
            }
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be a number");
    }

    public static long getPositiveLongArg(Map<String, Object> args, String key, boolean required) {
        long value = getLongArg(args, key, required);
        if (required && value <= 0) {
            throw new IllegalArgumentException(
                    "Argument '" + key + "' must be a positive number (got: " + value + ")");
        }
        return value;
    }

    public static int getIntArg(
            Map<String, Object> args, String key, int defaultValue, int min, int max) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object val = args.get(key);
        BigDecimal parsed;
        if (val instanceof Number n) {
            try {
                parsed = decimalValue(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Argument '" + key + "' must be a finite number", e);
            }
        } else if (val instanceof String s && !s.isBlank()) {
            try {
                parsed = BigDecimal.valueOf(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Argument '" + key + "' must be a number, got: " + s);
            }
        } else {
            throw new IllegalArgumentException("Argument '" + key + "' must be a number");
        }
        if (parsed.compareTo(BigDecimal.valueOf(min)) < 0) {
            return min;
        }
        return parsed.min(BigDecimal.valueOf(max)).intValue();
    }

    public static boolean getBooleanArg(
            Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object val = args.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            String normalized = s.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be a boolean");
    }

    private static BigDecimal decimalValue(Number number) {
        return switch (number) {
            case BigDecimal decimal -> decimal;
            case BigInteger integer -> new BigDecimal(integer);
            // Preserve the exact binary value, including the representable Long.MIN_VALUE boundary.
            case Double value -> new BigDecimal(value);
            case Float value -> new BigDecimal(value.doubleValue());
            default -> new BigDecimal(number.toString());
        };
    }
}

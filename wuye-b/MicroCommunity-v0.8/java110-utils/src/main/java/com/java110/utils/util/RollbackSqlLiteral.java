package com.java110.utils.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** SQL expressions for the legacy compensation protocol, never arbitrary toString(). */
public final class RollbackSqlLiteral {
    private RollbackSqlLiteral() { }

    public static String identifier(String value) {
        if (value == null) throw new IllegalArgumentException("Missing rollback identifier");
        String plain = value.trim();
        if (plain.startsWith("`") && plain.endsWith("`")) plain = plain.substring(1, plain.length() - 1);
        if (!plain.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid rollback identifier");
        return "`" + plain + "`";
    }

    /** Legacy actionObj included the optional mapper alias (for example pay_fee t). */
    public static String tableIdentifier(String value) {
        if (value == null) throw new IllegalArgumentException("Missing rollback table");
        String[] words = value.trim().split("\\s+");
        if (words.length == 1) return identifier(words[0]);
        if (words.length == 2) { identifier(words[1]); return identifier(words[0]); }
        if (words.length == 3 && "AS".equalsIgnoreCase(words[1])) { identifier(words[2]); return identifier(words[0]); }
        throw new IllegalArgumentException("Invalid rollback table");
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) { result.append(digits[(item & 255) >>> 4]); result.append(digits[item & 15]); }
        return result.toString();
    }

    public static String value(Object value) {
        if (value == null) return "NULL";
        if (value instanceof String || value instanceof Character) {
            return "CONVERT(X'" + hex(String.valueOf(value).getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
        }
        if (value instanceof byte[]) return "X'" + hex((byte[]) value) + "'";
        if (value instanceof Boolean) return ((Boolean) value) ? "1" : "0";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger) return value.toString();
        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new IllegalArgumentException("Non-finite rollback number");
            return value.toString();
        }
        if (value instanceof Timestamp) return value(((Timestamp) value).toString());
        if (value instanceof java.sql.Date || value instanceof java.sql.Time) return value(value.toString());
        if (value instanceof Date) return value(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format((Date) value));
        throw new IllegalArgumentException("Unsupported rollback value type: " + value.getClass().getName());
    }

    /** Old logs wrapped raw data in single quotes, without SQL escaping. */
    public static String fromLog(Object logged) {
        if (logged == null) return "NULL";
        if (!(logged instanceof String)) return value(logged);
        String expression = (String) logged;
        if (expression.matches("CONVERT\\(X'(?:[0-9A-F]{2})*' USING utf8mb4\\)")) return expression;
        if (expression.matches("X'(?:[0-9A-F]{2})*'")) return expression;
        if ("NULL".equalsIgnoreCase(expression)) return "NULL";
        if (expression.matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) {
            return new BigDecimal(expression).toPlainString();
        }
        if (expression.length() >= 2 && expression.charAt(0) == '\'' && expression.charAt(expression.length() - 1) == '\'') {
            return value(expression.substring(1, expression.length() - 1));
        }
        throw new IllegalArgumentException("Invalid rollback log expression");
    }
}

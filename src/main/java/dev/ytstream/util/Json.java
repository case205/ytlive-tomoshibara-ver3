package dev.ytstream.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依存ライブラリ無しの最小JSON実装(YouTube/Google APIの応答解析用)。
 * 値は Map(LinkedHashMap) / List / String / Double / Long / Boolean / null で表す。
 * 深さ・長さに上限を設け、異常な応答でスタックやメモリを食い潰さない。
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null json");
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("json is not an object");
        return (Map<String, Object>) v;
    }

    // ---------------- アクセサ(型が違えば既定値) ----------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> r) return (Map<String, Object>) r;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o, String key) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof List<?> r) return (List<Object>) r;
        return List.of();
    }

    public static String str(Object o, String key) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof String st) return st;
            if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        }
        return "";
    }

    public static long num(Object o, String key, long def) {
        if (o instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof Number n) return n.longValue();
            if (v instanceof String st) {
                try {
                    return Long.parseLong(st.trim());
                } catch (NumberFormatException ignored) {
                    return def;
                }
            }
        }
        return def;
    }

    public static boolean bool(Object o, String key, boolean def) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Boolean b) return b;
        return def;
    }

    /** "a.b.c" 形式で入れ子のオブジェクトをたどる。 */
    public static Object path(Object o, String dotted) {
        Object cur = o;
        for (String k : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(k);
        }
        return cur;
    }

    public static String pathStr(Object o, String dotted) {
        Object v = path(o, dotted);
        return v instanceof String st ? st : v == null ? "" : String.valueOf(v);
    }

    // ---------------- 書き出し ----------------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("too deep");
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String st) {
            quote(sb, st);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, depth + 1);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder sb, String st) {
        sb.append('"');
        for (int k = 0; k < st.length(); k++) {
            char c = st.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------------- 解析 ----------------

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected key");
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value(depth + 1));
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("expected , or }");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw err("expected , or ]");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) throw err("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("bad unicode escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("bad unicode escape");
                    }
                    i += 4;
                }
                default -> throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String n = s.substring(start, i);
        if (n.isEmpty() || n.equals("-")) throw err("unexpected character");
        try {
            if (!frac) {
                if (n.length() < 19) return Long.parseLong(n);
            }
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw err("bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("unexpected token");
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw err("unexpected end");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw err("expected " + c);
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON " + msg + " at " + i);
    }
}

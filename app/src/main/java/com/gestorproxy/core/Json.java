package com.gestorproxy.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mínimo: lo justo para guardar y leer los archivos de la app (sin librerías externas). */
public final class Json {
    private Json() {}

    public static final class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ lectura

    /** Devuelve Map (LinkedHashMap), List (ArrayList), String, Long, Double, Boolean o null. */
    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("Texto vacío");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) throw new JsonException("Texto sobrante en la posición " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        Object value(int depth) throws JsonException {
            if (depth > 64) throw new JsonException("Demasiado anidado");
            if (pos >= s.length()) throw new JsonException("Fin inesperado");
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        void expect(String word) throws JsonException {
            if (!s.startsWith(word, pos)) throw new JsonException("Valor inválido en la posición " + pos);
            pos += word.length();
        }

        Map<String, Object> object(int depth) throws JsonException {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            pos++; // {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new JsonException("Se esperaba un nombre en la posición " + pos);
                }
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new JsonException("Se esperaba ':' en la posición " + pos);
                }
                pos++;
                skipWs();
                m.put(key, value(depth + 1));
                skipWs();
                if (pos >= s.length()) throw new JsonException("Fin inesperado");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == '}') return m;
                throw new JsonException("Se esperaba ',' o '}' en la posición " + (pos - 1));
            }
        }

        List<Object> array(int depth) throws JsonException {
            List<Object> l = new ArrayList<Object>();
            pos++; // [
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) throw new JsonException("Fin inesperado");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == ']') return l;
                throw new JsonException("Se esperaba ',' o ']' en la posición " + (pos - 1));
            }
        }

        String string() throws JsonException {
            StringBuilder sb = new StringBuilder();
            pos++; // "
            while (true) {
                if (pos >= s.length()) throw new JsonException("Texto sin cerrar");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new JsonException("Carácter de control en un texto");
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw new JsonException("Texto sin cerrar");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new JsonException("Secuencia \\u incompleta");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) throw new JsonException("Secuencia \\u inválida");
                            code = code * 16 + d;
                        }
                        pos += 4;
                        sb.append((char) code);
                        break;
                    default:
                        throw new JsonException("Escape inválido: \\" + e);
                }
            }
        }

        Object number() throws JsonException {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            int digits = 0;
            while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
                pos++;
                digits++;
            }
            if (digits == 0) throw new JsonException("Valor inválido en la posición " + start);
            boolean isDouble = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                int fd = 0;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    fd++;
                }
                if (fd == 0) throw new JsonException("Número inválido en la posición " + start);
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                int ed = 0;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    ed++;
                }
                if (ed == 0) throw new JsonException("Número inválido en la posición " + start);
            }
            String num = s.substring(start, pos);
            try {
                if (!isDouble) {
                    try {
                        return Long.valueOf(Long.parseLong(num));
                    } catch (NumberFormatException big) {
                        return Double.valueOf(Double.parseDouble(num));
                    }
                }
                return Double.valueOf(Double.parseDouble(num));
            } catch (NumberFormatException e) {
                throw new JsonException("Número inválido en la posición " + start);
            }
        }
    }

    // ------------------------------------------------------------------ escritura

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        writeString(sb, s);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > 64) {
            sb.append("null");
            return;
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence) {
            writeString(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) v;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, arr[i], depth + 1);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ ayudas de lectura

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Object o, String def) {
        return o instanceof String ? (String) o : def;
    }

    public static long lng(Object o, long def) {
        if (o instanceof Long) return ((Long) o).longValue();
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Double) return (long) ((Double) o).doubleValue();
        return def;
    }

    public static boolean bool(Object o, boolean def) {
        return o instanceof Boolean ? ((Boolean) o).booleanValue() : def;
    }
}

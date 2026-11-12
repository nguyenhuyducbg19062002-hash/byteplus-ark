package ark.integration.byteplus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser/serializer (Map / List / String / Number / Boolean / null). */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != s.length()) throw p.error("Dữ liệu thừa sau JSON");
        return v;
    }

    /** Navigate nested maps/lists: keys are String (map key) or Integer (list index). */
    public static Object path(Object root, Object... keys) {
        Object cur = root;
        for (Object k : keys) {
            if (cur == null) return null;
            if (k instanceof String && cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(k);
            } else if (k instanceof Integer && cur instanceof List) {
                List<?> l = (List<?>) cur;
                int i = (Integer) k;
                cur = i >= 0 && i < l.size() ? l.get(i) : null;
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String str(Object root, Object... keys) {
        Object v = path(root, keys);
        return v == null ? null : String.valueOf(v);
    }

    public static Long lng(Object root, Object... keys) {
        Object v = path(root, keys);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    public static List<?> list(Object root, Object... keys) {
        Object v = path(root, keys);
        return v instanceof List ? (List<?>) v : List.of();
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote((String) o, sb);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(v, sb);
            }
            sb.append(']');
        } else {
            quote(o.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        IllegalArgumentException error(String m) {
            return new IllegalArgumentException(m + " (vị trí " + pos + ")");
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        char peek() {
            if (pos >= s.length()) throw error("JSON kết thúc đột ngột");
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        Object value() {
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("Ký tự không hợp lệ '" + c + "'");
            }
        }

        void literal(String w) {
            if (!s.startsWith(w, pos)) throw error("Mong đợi " + w);
            pos += w.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("Mong đợi tên khoá");
                String k = string();
                skipWs();
                if (next() != ':') throw error("Mong đợi ':'");
                skipWs();
                m.put(k, value());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw error("Mong đợi ',' hoặc '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw error("Mong đợi ',' hoặc ']'");
            }
        }

        String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw error("Escape \\u không hợp lệ");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw error("Escape không hợp lệ");
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            String t = s.substring(start, pos);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException big) {
                        return new BigDecimal(t);
                    }
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException ex) {
                throw error("Số không hợp lệ: " + t);
            }
        }
    }
}

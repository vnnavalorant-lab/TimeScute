package dev.timescute.core;

import java.util.*;

/** Leitor de JSON mínimo (objetos, listas, números, textos, booleanos, null). Sem dependências. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    /** Retorna Map, List, Double, String, Boolean ou null. Lança IllegalArgumentException se inválido. */
    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("conteúdo extra depois do JSON");
        return v;
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON inválido na posição " + i + ": " + msg);
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw err("fim inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("caractere inesperado '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("esperado " + w);
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw err("esperado nome de campo");
            String k = string();
            ws();
            if (!peek(':')) throw err("esperado ':'");
            i++; ws();
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            if (peek('}')) { i++; return m; }
            throw err("esperado ',' ou '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            if (peek(']')) { i++; return l; }
            throw err("esperado ',' ou ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("texto não fechado");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("escape incompleto");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("\\u incompleto");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException ex) { throw err("\\u inválido"); }
                    i += 4;
                    break;
                default: throw err("escape desconhecido");
            }
        }
    }

    private Double number() {
        int st = i;
        if (peek('-')) i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try { return Double.valueOf(s.substring(st, i)); }
        catch (NumberFormatException ex) { throw err("número inválido"); }
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    /** Escapa um texto para uso dentro de aspas JSON. */
    public static String quote(String t) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}

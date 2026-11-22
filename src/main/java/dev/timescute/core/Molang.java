package dev.timescute.core;

import java.util.Locale;

/**
 * Avaliador mínimo de Molang (as expressões que o Blockbench coloca nos keyframes).
 * Suporta + - * / ( ), comparações, && || !, condicional a ? b : c, math.* (trigonometria em GRAUS),
 * query.anim_time / life_time. Variáveis desconhecidas valem 0. Nunca lança em tempo de execução (divisão por zero = 0).
 */
public final class Molang {
    public interface Expr { double eval(double animTime); }

    public static final Expr ZERO = t -> 0;

    private final String s;
    private int i;

    private Molang(String s) { this.s = s; }

    public static Expr constant(double v) { return t -> v; }

    /** Compila; lança IllegalArgumentException se a expressão for inválida. */
    public static Expr parse(String text) {
        String src = text.trim().toLowerCase(Locale.ROOT);
        if (src.endsWith(";")) src = src.substring(0, src.length() - 1).trim();
        if (src.startsWith("return ")) src = src.substring(7);
        Molang p = new Molang(src);
        Expr e = p.ternary();
        p.ws();
        if (p.i != p.s.length()) throw p.err("texto sobrando");
        return e;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("Expressão inválida \"" + s + "\": " + m + " (posição " + i + ")");
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private boolean eat(String tok) {
        ws();
        if (s.startsWith(tok, i)) { i += tok.length(); return true; }
        return false;
    }

    private Expr ternary() {
        Expr c = or();
        if (eat("?")) {
            Expr a = ternary();
            if (!eat(":")) throw err("esperado ':'");
            Expr b = ternary();
            return t -> c.eval(t) != 0 ? a.eval(t) : b.eval(t);
        }
        return c;
    }

    private Expr or() {
        Expr l = and();
        while (eat("||")) { Expr a = l, b = and(); l = t -> (a.eval(t) != 0 || b.eval(t) != 0) ? 1 : 0; }
        return l;
    }

    private Expr and() {
        Expr l = equality();
        while (eat("&&")) { Expr a = l, b = equality(); l = t -> (a.eval(t) != 0 && b.eval(t) != 0) ? 1 : 0; }
        return l;
    }

    private Expr equality() {
        Expr l = relational();
        while (true) {
            if (eat("==")) { Expr a = l, b = relational(); l = t -> a.eval(t) == b.eval(t) ? 1 : 0; }
            else if (eat("!=")) { Expr a = l, b = relational(); l = t -> a.eval(t) != b.eval(t) ? 1 : 0; }
            else return l;
        }
    }

    private Expr relational() {
        Expr l = additive();
        while (true) {
            if (eat("<=")) { Expr a = l, b = additive(); l = t -> a.eval(t) <= b.eval(t) ? 1 : 0; }
            else if (eat(">=")) { Expr a = l, b = additive(); l = t -> a.eval(t) >= b.eval(t) ? 1 : 0; }
            else if (eat("<")) { Expr a = l, b = additive(); l = t -> a.eval(t) < b.eval(t) ? 1 : 0; }
            else if (eat(">")) { Expr a = l, b = additive(); l = t -> a.eval(t) > b.eval(t) ? 1 : 0; }
            else return l;
        }
    }

    private Expr additive() {
        Expr l = term();
        while (true) {
            if (eat("+")) { Expr a = l, b = term(); l = t -> a.eval(t) + b.eval(t); }
            else if (eat("-")) { Expr a = l, b = term(); l = t -> a.eval(t) - b.eval(t); }
            else return l;
        }
    }

    private Expr term() {
        Expr l = unary();
        while (true) {
            if (eat("*")) { Expr a = l, b = unary(); l = t -> a.eval(t) * b.eval(t); }
            else if (eat("/")) { Expr a = l, b = unary(); l = t -> { double d = b.eval(t); return d == 0 ? 0 : a.eval(t) / d; }; }
            else return l;
        }
    }

    private Expr unary() {
        if (eat("-")) { Expr e = unary(); return t -> -e.eval(t); }
        if (eat("+")) return unary();
        if (eat("!")) { Expr e = unary(); return t -> e.eval(t) == 0 ? 1 : 0; }
        return primary();
    }

    private Expr primary() {
        ws();
        if (i >= s.length()) throw err("fim inesperado");
        char c = s.charAt(i);
        if (c == '(') {
            i++;
            Expr e = ternary();
            if (!eat(")")) throw err("esperado ')'");
            return e;
        }
        if (Character.isDigit(c) || c == '.') {
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i < s.length() && s.charAt(i) == 'e' && i + 1 < s.length()
                    && (Character.isDigit(s.charAt(i + 1)) || ((s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+') && i + 2 < s.length() && Character.isDigit(s.charAt(i + 2))))) {
                i += 2;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            try { return constant(Double.parseDouble(s.substring(st, i))); }
            catch (NumberFormatException e) { throw err("número inválido"); }
        }
        if (Character.isLetter(c) || c == '_') {
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
            String name = s.substring(st, i);
            ws();
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                java.util.List<Expr> args = new java.util.ArrayList<>();
                ws();
                if (!eat(")")) {
                    do { args.add(ternary()); } while (eat(","));
                    if (!eat(")")) throw err("esperado ')'");
                }
                return call(name, args);
            }
            return variable(name);
        }
        throw err("caractere inesperado '" + c + "'");
    }

    private static String norm(String n) {
        if (n.startsWith("q.")) return "query." + n.substring(2);
        if (n.startsWith("m.")) return "math." + n.substring(2);
        if (n.startsWith("v.")) return "variable." + n.substring(2);
        return n;
    }

    private Expr variable(String raw) {
        String n = norm(raw);
        return switch (n) {
            case "query.anim_time", "query.life_time" -> t -> t;
            case "query.delta_time" -> constant(0.05);
            case "math.pi" -> constant(Math.PI);
            case "true" -> constant(1);
            case "false" -> constant(0);
            default -> ZERO; // variáveis e consultas desconhecidas: 0
        };
    }

    private Expr call(String raw, java.util.List<Expr> a) {
        String n = norm(raw);
        int k = a.size();
        switch (n) {
            case "math.sin": need(n, k, 1); return t -> Math.sin(Math.toRadians(a.get(0).eval(t)));
            case "math.cos": need(n, k, 1); return t -> Math.cos(Math.toRadians(a.get(0).eval(t)));
            case "math.abs": need(n, k, 1); return t -> Math.abs(a.get(0).eval(t));
            case "math.sqrt": need(n, k, 1); return t -> { double v = a.get(0).eval(t); return v < 0 ? 0 : Math.sqrt(v); };
            case "math.floor": need(n, k, 1); return t -> Math.floor(a.get(0).eval(t));
            case "math.ceil": need(n, k, 1); return t -> Math.ceil(a.get(0).eval(t));
            case "math.round": need(n, k, 1); return t -> Math.rint(a.get(0).eval(t));
            case "math.min": need(n, k, 2); return t -> Math.min(a.get(0).eval(t), a.get(1).eval(t));
            case "math.max": need(n, k, 2); return t -> Math.max(a.get(0).eval(t), a.get(1).eval(t));
            case "math.pow": need(n, k, 2); return t -> { double r = Math.pow(a.get(0).eval(t), a.get(1).eval(t)); return Double.isNaN(r) || Double.isInfinite(r) ? 0 : r; };
            case "math.mod": need(n, k, 2); return t -> { double d = a.get(1).eval(t); return d == 0 ? 0 : a.get(0).eval(t) % d; };
            case "math.clamp": need(n, k, 3); return t -> Math.max(a.get(1).eval(t), Math.min(a.get(2).eval(t), a.get(0).eval(t)));
            case "math.lerp": need(n, k, 3); return t -> { double x = a.get(0).eval(t), y = a.get(1).eval(t); return x + (y - x) * a.get(2).eval(t); };
            case "math.random": return t -> k >= 2 ? (a.get(0).eval(t) + a.get(1).eval(t)) / 2 : 0.5; // determinístico
            default: return ZERO; // função desconhecida: 0 (não derruba a importação)
        }
    }

    private void need(String n, int got, int want) {
        if (got != want) throw err(n + " espera " + want + " argumento(s)");
    }
}

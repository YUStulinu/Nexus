package nexus.core.util;

import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * A small arithmetic expression evaluator (recursive descent), for the Formula node.
 *
 * <pre>
 * expr    := term (('+' | '-') term)*
 * term    := unary (('*' | '/' | '%') unary)*
 * unary   := '-' unary | power             so -2^2 = -(2^2) = -4, as in mathematics
 * power   := primary ('^' unary)?          right-associative: 2^3^2 = 2^9
 * primary := number | name | name '(' expr (',' expr)* ')' | '(' expr ')'
 * </pre>
 * Names are variables from the given map, or the constants pi and e. Functions: sqrt, abs, exp, log
 * (natural), log10, sin, cos, tan, floor, ceil, round, min, max, pow.
 */
public final class Expression {
    private final String src;
    private int pos;
    private final Map<String, Double> vars;

    private Expression(String src, Map<String, Double> vars) {
        this.src = src;
        this.vars = vars;
    }

    public static double evaluate(String expression, Map<String, Double> variables) {
        var p = new Expression(expression, variables);
        double v = p.expr();
        p.skipSpaces();
        if (p.pos != p.src.length()) throw p.error("unexpected '" + p.src.charAt(p.pos) + "'");
        return v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at position " + (pos + 1) + " in \"" + src + "\"");
    }

    private void skipSpaces() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean eat(char c) {
        skipSpaces();
        if (pos < src.length() && src.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private double expr() {
        double v = term();
        while (true) {
            if (eat('+')) v += term();
            else if (eat('-')) v -= term();
            else return v;
        }
    }

    private double term() {
        double v = unary();
        while (true) {
            if (eat('*')) v *= unary();
            else if (eat('/')) v /= unary();
            else if (eat('%')) v %= unary();
            else return v;
        }
    }

    private double unary() {
        if (eat('-')) return -unary();
        if (eat('+')) return unary();
        return power();
    }

    private double power() {
        double base = primary();
        if (eat('^')) return Math.pow(base, unary());
        return base;
    }

    private double primary() {
        skipSpaces();
        if (eat('(')) {
            double v = expr();
            if (!eat(')')) throw error("expected ')'");
            return v;
        }
        if (pos >= src.length()) throw error("unexpected end");
        char c = src.charAt(pos);
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                int save = pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
                } else pos = save;
            }
            try {
                return Double.parseDouble(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String name = src.substring(start, pos);
            if (eat('(')) return call(name);
            if (vars.containsKey(name)) {
                Double v = vars.get(name);
                if (v == null) throw error("variable '" + name + "' has no value");
                return v;
            }
            return switch (name) {
                case "pi" -> Math.PI;
                case "e" -> Math.E;
                default -> throw error("unknown name '" + name + "'");
            };
        }
        throw error("unexpected '" + c + "'");
    }

    private double call(String name) {
        double a = expr();
        if (name.equals("min") || name.equals("max") || name.equals("pow")) {
            if (!eat(',')) throw error(name + " needs two arguments");
            double b = expr();
            if (!eat(')')) throw error("expected ')'");
            return switch (name) {
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                default -> Math.pow(a, b);
            };
        }
        if (!eat(')')) throw error("expected ')'");
        DoubleUnaryOperator f = switch (name) {
            case "sqrt" -> Math::sqrt;
            case "abs" -> Math::abs;
            case "exp" -> Math::exp;
            case "log" -> Math::log;
            case "log10" -> Math::log10;
            case "sin" -> Math::sin;
            case "cos" -> Math::cos;
            case "tan" -> Math::tan;
            case "floor" -> Math::floor;
            case "ceil" -> Math::ceil;
            case "round" -> x -> (double) Math.round(x);
            default -> throw error("unknown function '" + name + "'");
        };
        return f.applyAsDouble(a);
    }
}

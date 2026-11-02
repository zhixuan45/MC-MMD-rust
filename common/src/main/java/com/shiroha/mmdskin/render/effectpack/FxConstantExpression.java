package com.shiroha.mmdskin.render.effectpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/** Bounded evaluation of pure scalar/vector expressions; never executes FX code. */
final class FxConstantExpression {
    record Function(List<String> parameters, String expression) {
        Function {
            parameters = List.copyOf(parameters);
        }
    }

    private final Map<String, float[]> constants;
    private final Map<String, String> definitions;
    private final Map<String, Function> functions;
    private final Set<String> expanding = new HashSet<>();
    private int operations;
    private int depth;

    FxConstantExpression(Map<String, float[]> constants, Map<String, String> definitions,
                         Map<String, Function> functions) {
        this.constants = constants;
        this.definitions = definitions;
        this.functions = functions;
    }

    float[] evaluate(String expression, int width) throws IOException {
        if (width < 1 || width > 4) throw error("表达式目标宽度越界", "");
        operations = 0;
        depth = 0;
        expanding.clear();
        double[] value = parse(expression, Map.of());
        if (value.length != 1 && value.length != width) throw error("向量宽度不匹配", expression);
        float[] result = new float[width];
        for (int i = 0; i < width; i++) {
            result[i] = (float) value[value.length == 1 ? 0 : i];
            if (!Float.isFinite(result[i])) throw error("表达式结果超出有限浮点数范围", expression);
        }
        return result;
    }

    private double[] parse(String text, Map<String, double[]> locals) throws IOException {
        if (text.length() > 16384) throw error("表达式长度超限", "");
        Parser parser = new Parser(text, locals);
        double[] value = parser.expression();
        parser.space();
        if (parser.cursor != text.length()) throw error("表达式包含未支持的语法", text.substring(parser.cursor));
        return value;
    }

    private void enter() throws IOException {
        if (++operations > 8192 || ++depth > 64) throw error("表达式复杂度超限", "");
    }

    private static IOException error(String reason, String detail) {
        return new IOException(reason + (detail.isEmpty() ? "" : ": " + detail));
    }

    private double[] unary(double[] a, DoubleUnaryOperator operator) throws IOException {
        double[] result = a.clone();
        for (int i = 0; i < result.length; i++) {
            result[i] = operator.applyAsDouble(result[i]);
            if (!Double.isFinite(result[i])) throw error("表达式产生非有限数值", "");
        }
        return result;
    }

    private double[] binary(double[] a, double[] b, DoubleBinaryOperator operator) throws IOException {
        if (++operations > 8192) throw error("表达式复杂度超限", "");
        int width = Math.max(a.length, b.length);
        if ((a.length != 1 && a.length != width) || (b.length != 1 && b.length != width))
            throw error("向量宽度不匹配", "");
        double[] result = new double[width];
        for (int i = 0; i < width; i++) {
            result[i] = operator.applyAsDouble(a[a.length == 1 ? 0 : i], b[b.length == 1 ? 0 : i]);
            if (!Double.isFinite(result[i])) throw error("表达式产生非有限数值", "");
        }
        return result;
    }

    private double[] identifier(String name, Map<String, double[]> locals) throws IOException {
        if (locals.containsKey(name)) return locals.get(name);
        float[] constant = constants.get(name);
        if (constant != null) {
            double[] result = new double[constant.length];
            for (int i = 0; i < result.length; i++) result[i] = constant[i];
            return result;
        }
        String definition = definitions.get(name);
        if (definition == null) throw error("未知常量或运行时输入", name);
        if (!expanding.add(name)) throw error("循环常量/宏引用", name);
        try { return parse(definition, Map.of()); }
        finally { expanding.remove(name); }
    }

    private double[] call(String name, List<double[]> args) throws IOException {
        if (name.matches("(?:float|half)[234]?")) {
            int width = Character.isDigit(name.charAt(name.length() - 1))
                    ? name.charAt(name.length() - 1) - '0' : 1;
            int count = args.stream().mapToInt(a -> a.length).sum();
            if (args.size() == 1 && count == 1) {
                double[] result = new double[width];
                java.util.Arrays.fill(result, args.getFirst()[0]);
                return result;
            }
            if (count != width) throw error("向量构造器宽度不匹配", name);
            double[] result = new double[width];
            int offset = 0;
            for (double[] arg : args) {
                System.arraycopy(arg, 0, result, offset, arg.length);
                offset += arg.length;
            }
            return result;
        }
        Function function = functions.get(name);
        if (function != null) {
            if (args.size() != function.parameters().size()) throw error("宏参数数量不匹配", name);
            if (!expanding.add(name)) throw error("循环常量/宏引用", name);
            var locals = new java.util.LinkedHashMap<String, double[]>();
            for (int i = 0; i < args.size(); i++) locals.put(function.parameters().get(i), args.get(i));
            try { return parse(function.expression(), locals); }
            finally { expanding.remove(name); }
        }
        int arity = switch (name) {
            case "abs", "exp", "exp2", "sqrt", "saturate" -> 1;
            case "pow", "min", "max" -> 2;
            case "clamp", "lerp" -> 3;
            default -> throw error("未支持的常量函数", name);
        };
        if (args.size() != arity) throw error("函数参数数量不匹配", name);
        double[] a = args.getFirst();
        return switch (name) {
            case "abs" -> unary(a, Math::abs);
            case "exp" -> unary(a, Math::exp);
            case "exp2" -> unary(a, x -> Math.pow(2, x));
            case "sqrt" -> unary(a, Math::sqrt);
            case "saturate" -> unary(a, x -> Math.max(0, Math.min(1, x)));
            case "pow" -> binary(a, args.get(1), Math::pow);
            case "min" -> binary(a, args.get(1), Math::min);
            case "max" -> binary(a, args.get(1), Math::max);
            case "clamp" -> binary(binary(a, args.get(1), Math::max), args.get(2), Math::min);
            case "lerp" -> binary(a, binary(binary(args.get(1), a, (x, y) -> x - y),
                    args.get(2), (x, y) -> x * y), Double::sum);
            default -> throw new AssertionError(name);
        };
    }

    private final class Parser {
        private final String text;
        private final Map<String, double[]> locals;
        private int cursor;

        Parser(String text, Map<String, double[]> locals) {
            this.text = text;
            this.locals = locals;
        }

        void space() { while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++; }
        boolean take(char c) {
            space();
            if (cursor < text.length() && text.charAt(cursor) == c) { cursor++; return true; }
            return false;
        }

        double[] expression() throws IOException {
            enter();
            try {
                double[] value = product();
                while (true) {
                    if (take('+')) value = binary(value, product(), Double::sum);
                    else if (take('-')) value = binary(value, product(), (a, b) -> a - b);
                    else return value;
                }
            } finally { depth--; }
        }

        double[] product() throws IOException {
            double[] value = atom();
            while (true) {
                if (take('*')) value = binary(value, atom(), (a, b) -> a * b);
                else if (take('/')) value = binary(value, atom(), (a, b) -> a / b);
                else return value;
            }
        }

        double[] atom() throws IOException {
            enter();
            try {
                if (take('+')) return atom();
                if (take('-')) return unary(atom(), a -> -a);
                if (take('(')) {
                    double[] value = expression();
                    if (!take(')')) throw error("表达式缺少右括号", "");
                    return value;
                }
                space();
                if (cursor >= text.length()) throw error("表达式缺少操作数", "");
                int start = cursor;
                char c = text.charAt(cursor);
                if (Character.isLetter(c) || c == '_') {
                    cursor++;
                    while (cursor < text.length() && (Character.isLetterOrDigit(text.charAt(cursor)) || text.charAt(cursor) == '_')) cursor++;
                    String name = text.substring(start, cursor);
                    if (!take('(')) return identifier(name, locals);
                    var args = new ArrayList<double[]>();
                    if (!take(')')) {
                        do {
                            if (args.size() >= 8) throw error("函数参数数量超限", name);
                            args.add(expression());
                        } while (take(','));
                        if (!take(')')) throw error("函数调用缺少右括号", name);
                    }
                    return call(name, args);
                }
                if (!Character.isDigit(c) && c != '.') throw error("表达式包含未支持的语法", text.substring(cursor));
                while (cursor < text.length() && (Character.isDigit(text.charAt(cursor)) || text.charAt(cursor) == '.')) cursor++;
                if (cursor < text.length() && (text.charAt(cursor) == 'e' || text.charAt(cursor) == 'E')) {
                    cursor++;
                    if (cursor < text.length() && (text.charAt(cursor) == '+' || text.charAt(cursor) == '-')) cursor++;
                    while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) cursor++;
                }
                String literal = text.substring(start, cursor);
                if (cursor < text.length() && (text.charAt(cursor) == 'f' || text.charAt(cursor) == 'F')) cursor++;
                try {
                    double number = Double.parseDouble(literal);
                    if (!Double.isFinite(number)) throw new NumberFormatException();
                    return new double[]{number};
                } catch (NumberFormatException e) { throw error("无效的有限数值常量", literal); }
            } finally { depth--; }
        }
    }
}

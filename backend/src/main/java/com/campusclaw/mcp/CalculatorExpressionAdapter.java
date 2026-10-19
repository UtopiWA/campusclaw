package com.campusclaw.mcp;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把明确的计算请求规范化为演示计算器可接受的受限表达式。 */
public final class CalculatorExpressionAdapter {
    private static final Pattern EXPRESSION = Pattern.compile(
            "(?:(?:sqrt|abs|round|min|max|pow)|[0-9e+\\-*/%.(),\\s]){1,200}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:请问?\\s*)?(?:帮我\\s*)?(?:计算(?:一下)?|算(?:一下)?|求值|calculate|compute|what\\s+is)\\s*[:：]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX = Pattern.compile(
            "\\s*(?:的)?(?:结果|等于多少|是多少|多少)?[?？。]?\\s*$");
    private static final Pattern NATURAL_POWER = Pattern.compile(
            "^(.+?)的([+\\-]?\\d+)次(?:幂|方)$");
    private static final Pattern NATURAL_SQUARE = Pattern.compile("^(.+?)的(?:平方|二次方)$");
    private static final Pattern NATURAL_CUBE = Pattern.compile("^(.+?)的(?:立方|三次方)$");
    private static final Pattern NATURAL_ROOT = Pattern.compile("^(?:根号|√)(.+)$");
    private static final Pattern NATURAL_SQUARE_ROOT = Pattern.compile("^(.+?)的平方根$");
    private static final Pattern ATOMIC_EXPRESSION = Pattern.compile(
            "[+\\-]?(?:\\d+(?:\\.\\d+)?|(?:sqrt|abs|round)\\(.+\\)|\\(.+\\))",
            Pattern.CASE_INSENSITIVE);

    private CalculatorExpressionAdapter() {
    }

    public static Optional<String> adapt(String question) {
        if (question == null) return Optional.empty();
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC)
                .replace('×', '*').replace('÷', '/');
        String expression = PREFIX.matcher(normalized).replaceFirst("");
        expression = SUFFIX.matcher(expression).replaceFirst("").trim();
        expression = translateNaturalExpression(expression).replace("^", "**");
        if (!EXPRESSION.matcher(expression).matches()
                || expression.codePointCount(0, expression.length()) > 200) {
            return Optional.empty();
        }
        return Optional.of(expression);
    }

    private static String translateNaturalExpression(String value) {
        String compact = value.replaceAll("\\s+", "");
        Matcher squareRoot = NATURAL_SQUARE_ROOT.matcher(compact);
        if (squareRoot.matches()) return function("sqrt", squareRoot.group(1));
        Matcher root = NATURAL_ROOT.matcher(compact);
        if (root.matches()) return function("sqrt", root.group(1));
        Matcher power = NATURAL_POWER.matcher(compact);
        if (power.matches()) return power(power.group(1), power.group(2));
        Matcher square = NATURAL_SQUARE.matcher(compact);
        if (square.matches()) return power(square.group(1), "2");
        Matcher cube = NATURAL_CUBE.matcher(compact);
        if (cube.matches()) return power(cube.group(1), "3");
        return namedOperators(value);
    }

    private static String function(String name, String argument) {
        String normalized = namedOperators(argument);
        return EXPRESSION.matcher(normalized).matches() ? name + "(" + normalized + ")" : argument;
    }

    private static String power(String base, String exponent) {
        String normalizedBase = namedOperators(base);
        if (!EXPRESSION.matcher(normalizedBase).matches()) return base + "的" + exponent + "次幂";
        String safeBase = ATOMIC_EXPRESSION.matcher(normalizedBase).matches()
                ? normalizedBase : "(" + normalizedBase + ")";
        return safeBase + " ** " + exponent;
    }

    private static String namedOperators(String value) {
        return value.replace("乘以", " * ").replace("除以", " / ")
                .replace("加上", " + ").replace("减去", " - ")
                .replace("加", " + ").replace("减", " - ")
                .replace("乘", " * ").replace("除", " / ")
                .replace("取余", " % ").trim();
    }
}

package com.oj.judge.service.support;

import java.util.LinkedHashMap;
import java.util.Map;

/** AI 的失败特征只包含类型和符号；截断的隐藏输出也可能是完整答案。 */
public final class DiagnosisSummary {
    private DiagnosisSummary() { }
    public static Map<String, Object> sanitize(Map<String, Object> raw, boolean publicCase) {
        if (publicCase) return raw;
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : new String[]{"status", "seqNo", "kind", "expectedType", "actualType"}) {
            if (raw.containsKey(name)) result.put(name, raw.get(name));
        }
        if (raw.containsKey("expectedOut")) result.put("expectedType", outputType(String.valueOf(raw.get("expectedOut"))));
        if (raw.containsKey("actualOut")) result.put("actualType", outputType(String.valueOf(raw.get("actualOut"))));
        return result;
    }
    public static String outputType(String output) {
        if (output == null || output.isBlank()) return "EMPTY";
        String text = output.strip();
        if (text.matches("[+-]?\\d+(\\.\\d+)?")) {
            if (text.matches("[+-]?0+(\\.0+)?")) return "ZERO";
            return text.startsWith("-") ? "NEGATIVE_NUMBER" : "POSITIVE_NUMBER";
        }
        return "TEXT_OR_MULTIPLE_VALUES";
    }
}

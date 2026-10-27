package com.oj.problem.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB 里 JSON 列（{@code tags} / {@code sample_cases} / {@code code_templates} / {@code ai_stages}）的解析器。
 *
 * <p><b>为什么容错而不抛：</b>这几列是导入脚本写进去的（scripts/import_problems.py）。
 * 一条脏数据（比如 tags 写成裸字符串 "动态规划" 而不是数组）不应该让整个题库列表 500——
 * 列表是学生首页，挂一个就全站白屏。所以统一「解析失败 → 返回空集合 + WARN 日志」，
 * 页面退化成「这道题没有标签」，而不是「题库服务挂了」。</p>
 *
 * <p>反过来的方向（{@code toJson}）不做容错：写库的 JSON 必须是我们自己序列化的，
 * 出错说明是代码 bug，直接抛。</p>
 *
 * @author oj-cloud
 */
public final class JsonSupport {

    private static final Logger log = LoggerFactory.getLogger(JsonSupport.class);

    /** 全站共用一个 ObjectMapper：它是线程安全的，每次 new 反而慢 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    /**
     * 解析字符串数组，如 {@code ["动态规划","前缀和"]}。
     *
     * @param raw DB 原文，可为 null/空
     * @return 永不为 null 的列表；解析失败返回空列表
     */
    public static List<String> toStringList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            // 兼容老数据：单值不带引号/不带方括号，如 tags = 动态规划
            String trimmed = raw.trim();
            if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
                List<String> single = new ArrayList<>(1);
                single.add(trimmed);
                return single;
            }
            log.warn("JSON 数组解析失败，已降级为空列表：{}", abbreviate(raw), e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析对象，如 {@code {"cpp":"...","java":"..."}}。
     *
     * @param raw       DB 原文
     * @param valueType 目标类型
     * @param <T>       目标泛型
     * @return 解析结果；解析失败返回 {@code defaultValue}
     */
    public static <T> T toObject(String raw, Class<T> valueType, T defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            T parsed = MAPPER.readValue(raw, valueType);
            return parsed == null ? defaultValue : parsed;
        } catch (Exception e) {
            log.warn("JSON 对象解析失败，已使用默认值：{}", abbreviate(raw), e);
            return defaultValue;
        }
    }

    /**
     * 解析「数组套对象」，如样例 {@code [{"input":"1 2","output":"3"}]}。
     *
     * @param raw         DB 原文
     * @param elementType 元素类型
     * @param <T>         元素泛型
     * @return 永不为 null 的列表；解析失败返回空列表
     */
    public static <T> List<T> toList(String raw, Class<T> elementType) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<T> parsed = MAPPER.readValue(raw,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            log.warn("JSON 数组对象解析失败，已降级为空列表：{}", abbreviate(raw), e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析整数数组，如 {@code [1,2,3,4]}（ai_stages）。
     *
     * @param raw DB 原文
     * @return 永不为 null 的列表
     */
    public static List<Integer> toIntList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Integer> parsed = MAPPER.readValue(raw, new TypeReference<List<Integer>>() {
            });
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception e) {
            log.warn("JSON 整数数组解析失败，已降级为空列表：{}", abbreviate(raw), e);
            return new ArrayList<>();
        }
    }

    /**
     * 解析字符串到 Map（供需要透传原始结构的地方用）。
     *
     * @param raw DB 原文
     * @return 永不为 null 的 Map
     */
    public static Map<String, Object> toMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("JSON Map 解析失败，已降级为空 Map：{}", abbreviate(raw), e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析字符串到 {@code Map<String,String>}（代码模板就是这种形状：
     * {@code {"cpp":"...","java":"...","python":"..."}}）。
     *
     * <p>与 {@link #toMap} 的区别：这里把非字符串值统一 {@code String.valueOf} 掉，
     * 保证返回类型对前端稳定——前端 {@code codeTemplates.cpp} 直接塞进编辑器，
     * 拿到一个数字或对象都会把编辑器搞崩。</p>
     *
     * @param raw DB 原文
     * @return 永不为 null 的 Map；解析失败返回空 Map
     */
    public static Map<String, String> toStringMap(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (parsed == null) {
                return result;
            }
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            return result;
        } catch (Exception e) {
            log.warn("JSON 字符串 Map 解析失败，已降级为空 Map：{}", abbreviate(raw), e);
            return result;
        }
    }

    /** 日志里截断长文本，避免一条脏数据刷满日志 */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 120 ? raw : raw.substring(0, 120) + "...(len=" + raw.length() + ")";
    }
}

package com.oj.problem.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从题面 Markdown 里抽取「输入格式 / 输出格式」两个小节。
 *
 * <p><b>为什么要在服务端抽：</b>契约 02 §3.3 的详情响应把 {@code inputSpec} / {@code outputSpec}
 * 作为独立字段下发，但库里只有一整篇 {@code content}（含「## 题目描述」「## 输入格式」
 * 「## 输出格式」「## 样例说明」「## 数据范围与提示」等小节）。前端如果自己去切，
 * 每页都要写一遍正则，且各页口径必然不一致。</p>
 *
 * <p><b>抽不到时返回空串而不是 null：</b>前端 {@code x-text} 渲染 null 会显示空白，
 * 但 {@code if (!spec)} 之类的判空又要处理两种假值。统一给空串，前端只判一次。</p>
 *
 * <p><b>已知数据现状：</b>1008 道题里 1002 道有「## 输入格式」、1001 道有「## 输出格式」，
 * 少数「输出 Hello, World!」这类题面本来就没有输入小节——这类题返回空串是正确行为，
 * 不是解析 bug。</p>
 *
 * @author oj-cloud
 */
public final class MarkdownSections {

    /** 输入格式小节标题的候选写法（导入脚本与人工录入都用过） */
    private static final String[] INPUT_TITLES = {"输入格式", "输入描述", "输入"};

    /** 输出格式小节标题的候选写法 */
    private static final String[] OUTPUT_TITLES = {"输出格式", "输出描述", "输出"};

    /** 任意二级/三级标题，用于确定小节的结束位置 */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,3}\\s+(.+?)\\s*$");

    private MarkdownSections() {
    }

    /**
     * 抽取「输入格式」小节正文。
     *
     * @param markdown 题面全文，可为 null
     * @return 小节正文（已 trim）；抽不到返回空串
     */
    public static String extractInputSpec(String markdown) {
        return extractSection(markdown, INPUT_TITLES);
    }

    /**
     * 抽取「输出格式」小节正文。
     *
     * @param markdown 题面全文，可为 null
     * @return 小节正文（已 trim）；抽不到返回空串
     */
    public static String extractOutputSpec(String markdown) {
        return extractSection(markdown, OUTPUT_TITLES);
    }

    /**
     * 按标题候选词抽取小节。
     *
     * <p>实现要点：先找出所有 {@code ##} / {@code ###} 标题的位置，再拿目标标题的
     * 结束位置当作下一个标题的起始位置——这样「输入格式」小节里即使用
     * {@code ```} 代码块包着样例，也不会被误截断。</p>
     *
     * @param markdown 全文
     * @param titles   标题候选（按优先级）
     * @return 小节正文，抽不到返回空串
     */
    private static String extractSection(String markdown, String[] titles) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Matcher matcher = HEADING.matcher(markdown);
        int start = -1;
        int end = markdown.length();
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (start < 0) {
                if (matches(title, titles)) {
                    start = matcher.end();
                }
            } else {
                // 已定位到目标小节，当前这个标题就是它的边界
                end = matcher.start();
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        return markdown.substring(start, end).trim();
    }

    /** 标题是否命中候选词（允许「输入格式」后面带括号说明，如「输入格式（Input）」） */
    private static boolean matches(String title, String[] titles) {
        for (String candidate : titles) {
            if (title.equals(candidate) || title.startsWith(candidate)) {
                return true;
            }
        }
        return false;
    }
}

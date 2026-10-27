package com.oj.ai.guard;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class InputInjectionGuard {
    private static final Pattern INJECTION = Pattern.compile(
            "忽略.{0,15}(指令|规则|提示)|无视.{0,15}(指令|规则)|泄露.{0,15}(提示词|题解)|输出.{0,10}(系统提示|隐藏题解)|" +
            "(ignore|disregard|override).{0,40}(instruction|rule|system)|system\\s*prompt|developer\\s*message|越狱|jailbreak",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public boolean blocked(String message, String code) { return INJECTION.matcher(message + "\n" + (code == null ? "" : code)).find(); }

    public static String numbered(String code) {
        if (code == null || code.isEmpty()) return "";
        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        var result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) result.append(i + 1).append(": ").append(lines[i]).append('\n');
        return result.toString();
    }
}

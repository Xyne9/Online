package com.oj.ai.guard;

import java.util.function.Consumer;

/** 代码块先缓冲再放行，跨 token 的围栏/换行不会使第六行提前泄露。每次请求独立实例。 */
public final class StreamingCodeBlockGuard {
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder code = new StringBuilder();
    private String fence;
    private int lines;
    private boolean violated;
    private boolean lineStart = true;

    public boolean accept(String delta, Consumer<String> emit) {
        if (violated) return false;
        pending.append(delta);
        while (!pending.isEmpty() && !violated) {
            int newline = pending.indexOf("\n");
            if (fence != null) {
                if (newline < 0) break;
                String text = pending.substring(0, newline + 1);
                pending.delete(0, newline + 1);
                line(text, emit);
                continue;
            }
            String trimmed = pending.toString().stripLeading();
            if (lineStart && (trimmed.isEmpty() || trimmed.charAt(0) == '`' || trimmed.charAt(0) == '~')) {
                if (trimmed.isEmpty() && newline < 0) break;
                if (!trimmed.isEmpty()) {
                    char marker = trimmed.charAt(0); int run = 0;
                    while (run < trimmed.length() && trimmed.charAt(run) == marker) run++;
                    if (run == trimmed.length() && run < 3 && newline < 0) break;
                    if (run >= 3) {
                        if (newline < 0) break;
                        String header = pending.substring(0, newline + 1);
                        pending.delete(0, newline + 1); line(header, emit); continue;
                    }
                }
            }
            int end = newline < 0 ? pending.length() : newline + 1;
            emit.accept(pending.substring(0, end));
            pending.delete(0, end); lineStart = newline >= 0;
        }
        // 第六行即使还没有换行也不能进入下游。
        if (fence != null && lines >= 5 && !pending.isEmpty()
                && !pending.toString().stripLeading().startsWith(fence.substring(0, 1))) violated = true;
        return !violated;
    }

    private void line(String text, Consumer<String> emit) {
        String trimmed = text.strip();
        if (fence == null) {
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                char marker = trimmed.charAt(0);
                int length = 0;
                while (length < trimmed.length() && trimmed.charAt(length) == marker) length++;
                fence = String.valueOf(marker).repeat(length);
                code.append(text); lines = 0;
            } else emit.accept(text);
        } else if (trimmed.matches(java.util.regex.Pattern.quote(fence) + "[" + fence.charAt(0) + "]*")) {
            code.append(text); emit.accept(code.toString()); code.setLength(0); fence = null; lineStart = true;
        } else {
            code.append(text);
            if (++lines > 5) violated = true;
        }
    }

    public boolean finish(Consumer<String> emit) {
        if (violated) return false;
        if (!pending.isEmpty()) { line(pending.toString(), emit); pending.setLength(0); }
        if (violated) return false;
        if (!code.isEmpty()) emit.accept(code.toString());
        return true;
    }
}

package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 1: lexical normalization + 64 bit SimHash + a parsed syntax AST.
 * The parser implements the statement/expression grammar used by introductory C++/Java/Python:
 * blocks, declarations, functions, branches, loops and precedence-aware expressions.
 * This is a reduced syntax AST, not a compiler/type checker; no token-count proxy is called AST.
 */
@Component
public class CodeSimilarityAnalyzer {
    private static final Set<String> KEYWORDS = Set.of("if", "else", "elif", "for", "while", "do", "return",
            "break", "continue", "switch", "case", "default", "try", "catch", "finally", "throw", "throws",
            "class", "struct", "public", "private", "protected", "static", "final", "const", "void", "int",
            "long", "double", "float", "char", "bool", "boolean", "short", "unsigned", "signed", "new",
            "delete", "def", "in", "and", "or", "not", "True", "False", "None", "true", "false", "null",
            "import", "from", "package", "using", "namespace", "include", "pass", "assert", "with", "as");
    private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry("=", 1), Map.entry("+=", 1), Map.entry("-=", 1), Map.entry("*=", 1), Map.entry("/=", 1),
            Map.entry("%=", 1), Map.entry("?", 2), Map.entry("||", 3), Map.entry("or", 3), Map.entry("&&", 4),
            Map.entry("and", 4), Map.entry("|", 5), Map.entry("^", 6), Map.entry("&", 7), Map.entry("==", 8),
            Map.entry("!=", 8), Map.entry("<", 9), Map.entry(">", 9), Map.entry("<=", 9), Map.entry(">=", 9),
            Map.entry("in", 9), Map.entry("<<", 10), Map.entry(">>", 10), Map.entry("+", 11), Map.entry("-", 11),
            Map.entry("*", 12), Map.entry("/", 12), Map.entry("%", 12), Map.entry("//", 12), Map.entry("**", 13));

    public record Fingerprint(long simHash, Map<String, Integer> astFeatures, List<String> normalizedTokens) { }
    public record Similarity(double simHash, double ast, double overall) { }
    private record Token(String text, String normalized, int line) { }
    private record Node(String kind, List<Node> children) {
        Node(String kind, Node... children) { this(kind, List.of(children)); }
    }

    public Fingerprint fingerprint(String code, String language) {
        if (code == null || code.isBlank()) throw BusinessException.badRequest("查重代码为空");
        if (!Set.of("cpp", "java", "python").contains(language)) throw BusinessException.badRequest("不支持的查重语言");
        List<Token> tokens = lex(code, language);
        if (tokens.size() < 3) throw BusinessException.badRequest("查重代码有效语法过少");
        List<String> normalized = tokens.stream().filter(t -> !t.text().equals("\n")).map(Token::normalized).toList();
        Parser parser = new Parser(tokens, language);
        Node root = parser.block(false, 0);
        if (parser.has()) throw BusinessException.badRequest("代码存在未匹配的结束括号");
        Map<String, Integer> features = new HashMap<>();
        features(root, features);
        if (features.isEmpty()) throw BusinessException.badRequest("无法解析代码的语法结构");
        return new Fingerprint(simHash(normalized), Map.copyOf(features), normalized);
    }

    public Similarity compare(Fingerprint a, Fingerprint b) {
        double hash = (64 - Long.bitCount(a.simHash() ^ b.simHash())) * 100.0 / 64;
        int totalA = a.astFeatures().values().stream().mapToInt(Integer::intValue).sum();
        int totalB = b.astFeatures().values().stream().mapToInt(Integer::intValue).sum();
        int intersection = a.astFeatures().entrySet().stream()
                .mapToInt(e -> Math.min(e.getValue(), b.astFeatures().getOrDefault(e.getKey(), 0))).sum();
        double ast = 200.0 * intersection / (totalA + totalB);
        return new Similarity(round(hash), round(ast), round(.4 * hash + .6 * ast));
    }

    public String normalizedLine(String line, String language) {
        return String.join(" ", lex(line, language).stream().filter(t -> !t.text.equals("\n"))
                .map(t -> t.normalized.startsWith("v") && t.text.matches("[A-Za-z_$][\\w$]*") ? "ID" : t.normalized).toList());
    }
    public String[] normalizedLines(String code, String language) {
        StringBuilder[] lines = new StringBuilder[code.split("\\R", -1).length];
        for (int i = 0; i < lines.length; i++) lines[i] = new StringBuilder();
        for (Token token : lex(code, language)) {
            if (token.text.equals("\n")) continue;
            String normalized = token.normalized.startsWith("v") && !KEYWORDS.contains(token.text) ? "ID" : token.normalized;
            lines[Math.min(token.line - 1, lines.length - 1)].append(normalized).append(' ');
        }
        return Arrays.stream(lines).map(StringBuilder::toString).toArray(String[]::new);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private static void features(Node node, Map<String, Integer> out) {
        // Parent/child edges and ordered shallow subtrees retain actual syntax and nesting.
        if (!node.children.isEmpty()) {
            String key = node.kind + "(" + String.join(",", node.children.stream().map(Node::kind).toList()) + ")";
            out.merge(key, 2, Integer::sum);
            for (Node child : node.children) out.merge(node.kind + "/" + child.kind, 1, Integer::sum);
        }
        for (Node child : node.children) features(child, out);
    }
    private static long simHash(List<String> tokens) {
        int[] bits = new int[64];
        for (int i = 0; i < tokens.size(); i++) {
            String gram = String.join(" ", tokens.subList(i, Math.min(i + 3, tokens.size())));
            long hash = 0xcbf29ce484222325L;
            for (char c : gram.toCharArray()) { hash ^= c; hash *= 0x100000001b3L; }
            hash ^= hash >>> 33; hash *= 0xff51afd7ed558ccdL; hash ^= hash >>> 33;
            for (int j = 0; j < 64; j++) bits[j] += ((hash >>> j) & 1) == 1 ? 1 : -1;
        }
        long result = 0;
        for (int j = 0; j < 64; j++) if (bits[j] >= 0) result |= 1L << j;
        return result;
    }

    private static List<Token> lex(String code, String language) {
        List<Token> out = new ArrayList<>(); Map<String, String> names = new LinkedHashMap<>();
        boolean python = "python".equals(language); int line = 1; boolean lineStart = true;
        Deque<Integer> indents = new ArrayDeque<>(); indents.push(0); int brackets = 0;
        for (int i = 0; i < code.length();) {
            char c = code.charAt(i);
            if (lineStart && python && brackets == 0) {
                int start = i, indent = 0;
                while (i < code.length() && (code.charAt(i) == ' ' || code.charAt(i) == '\t')) { indent += code.charAt(i++) == '\t' ? 4 : 1; }
                if (i >= code.length()) break;
                c = code.charAt(i);
                if (c != '\n' && c != '\r' && c != '#') {
                    if (indent > indents.peek()) { indents.push(indent); out.add(new Token("{", "{", line)); }
                    while (indent < indents.peek()) { indents.pop(); out.add(new Token("}", "}", line)); }
                    if (indent != indents.peek()) throw BusinessException.badRequest("Python 缩进无法解析，行 " + line);
                }
                lineStart = false;
                if (start != i) continue;
            }
            if (c == '\n') { if (!python || brackets == 0) out.add(new Token("\n", "\n", line)); i++; line++; lineStart = true; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            lineStart = false;
            if ((python && c == '#') || (!python && c == '#' && (i == 0 || code.substring(Math.max(0, code.lastIndexOf('\n', i) + 1), i).isBlank()))
                    || (!python && c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/')) {
                while (i < code.length() && code.charAt(i) != '\n') i++; continue;
            }
            if (!python && c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                i += 2; boolean closed = false;
                while (i < code.length()) { if (i + 1 < code.length() && code.startsWith("*/", i)) { i += 2; closed = true; break; } if (code.charAt(i++) == '\n') line++; }
                if (!closed) throw BusinessException.badRequest("代码注释未闭合");
                continue;
            }
            if (c == '\'' || c == '"') {
                int start = i, startLine = line; boolean triple = python && code.startsWith("" + c + c + c, i);
                i += triple ? 3 : 1; boolean closed = false;
                while (i < code.length()) {
                    if (code.charAt(i) == '\\') { i = Math.min(code.length(), i + 2); continue; }
                    if (triple ? code.startsWith("" + c + c + c, i) : code.charAt(i) == c) { i += triple ? 3 : 1; closed = true; break; }
                    if (code.charAt(i++) == '\n') line++;
                }
                if (!closed) throw BusinessException.badRequest("代码字符串未闭合");
                out.add(new Token(code.substring(start, i), "LITERAL", startLine)); continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) i++;
                String name = code.substring(start, i);
                out.add(new Token(name, KEYWORDS.contains(name) ? name : names.computeIfAbsent(name, n -> "v" + names.size()), line)); continue;
            }
            if (Character.isDigit(c)) {
                int start = i++;
                while (i < code.length() && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '.' || code.charAt(i) == '_')) i++;
                out.add(new Token(code.substring(start, i), "NUMBER", line)); continue;
            }
            String op = String.valueOf(c);
            for (String multi : List.of(">>>", "<<=", ">>=", "==", "!=", "<=", ">=", "++", "--", "+=", "-=", "*=", "/=", "%=", "&&", "||", "<<", ">>", "->", "::", "**", "//", ":="))
                if (code.startsWith(multi, i)) { op = multi; break; }
            out.add(new Token(op, op, line)); i += op.length();
            if (op.equals("(") || op.equals("[")) brackets++;
            if (op.equals(")") || op.equals("]")) brackets--;
        }
        while (python && indents.size() > 1) { indents.pop(); out.add(new Token("}", "}", line)); }
        return out;
    }

    private static class Parser {
        final List<Token> tokens; final boolean python; int at;
        Parser(List<Token> tokens, String language) { this.tokens = tokens; python = "python".equals(language); }
        boolean has() { return at < tokens.size(); }
        String peek() { return has() ? tokens.get(at).text : ""; }
        boolean eat(String s) { if (peek().equals(s)) { at++; return true; } return false; }
        void separators() { while (eat("\n") || eat(";")) { } }
        Node block(boolean closing, int depth) {
            if (depth > 128) throw BusinessException.badRequest("代码语法嵌套过深");
            List<Node> children = new ArrayList<>(); separators();
            while (has() && !peek().equals("}")) { int start = at; children.add(statement(depth + 1)); if (at == start) at++; separators(); }
            if (closing && !eat("}")) throw BusinessException.badRequest("代码块未闭合");
            return new Node("BLOCK", children);
        }
        Node statement(int depth) {
            separators();
            if (eat("{")) return block(true, depth);
            String first = peek();
            if (Set.of("for", "while", "if", "elif", "switch", "catch", "with").contains(first)) {
                at++; List<Node> nodes = new ArrayList<>();
                if (eat("(")) { nodes.add(expressions(Set.of(")"))); require(")"); }
                else nodes.add(expressions(Set.of(":", "\n", "{")));
                eat(":"); separators(); nodes.add(statement(depth + 1));
                if ((first.equals("if") || first.equals("elif")) && eat("else")) { eat(":"); separators(); nodes.add(statement(depth + 1)); }
                return new Node(Set.of("for", "while").contains(first) ? "LOOP" : first.toUpperCase(Locale.ROOT), nodes);
            }
            if (eat("do")) { Node body = statement(depth + 1); eat("while"); require("("); Node cond = expressions(Set.of(")")); require(")"); return new Node("LOOP", cond, body); }
            if (Set.of("return", "throw", "assert").contains(first)) { at++; return new Node(first.toUpperCase(Locale.ROOT), expressions(Set.of(";", "\n", "}"))); }
            if (Set.of("else", "try", "finally").contains(first)) { at++; eat(":"); separators(); return new Node(first.toUpperCase(Locale.ROOT), statement(depth + 1)); }
            if (Set.of("break", "continue", "pass").contains(first)) { at++; return new Node(first.toUpperCase(Locale.ROOT)); }
            List<Token> header = takeUntil(Set.of(";", "\n", "{", "}"));
            if (python && !header.isEmpty() && header.get(header.size() - 1).text.equals(":")) header = header.subList(0, header.size() - 1);
            separators();
            if (eat("{")) {
                boolean function = header.stream().anyMatch(t -> t.text.equals("(")) || first.equals("def");
                return new Node(function ? "FUNCTION" : "TYPE", expressionTokens(header), block(true, depth));
            }
            return new Node("STATEMENT", expressionTokens(header));
        }
        void require(String s) { if (!eat(s)) throw BusinessException.badRequest("代码语法缺少 " + s); }
        List<Token> takeUntil(Set<String> ends) {
            int start = at, bracket = 0;
            while (has()) {
                String s = peek();
                if (bracket == 0 && ends.contains(s)) break;
                if (s.equals("(") || s.equals("[")) bracket++;
                if (s.equals(")") || s.equals("]")) { if (bracket == 0) break; bracket--; }
                at++;
            }
            if (bracket != 0) throw BusinessException.badRequest("代码括号未闭合");
            return tokens.subList(start, at);
        }
        Node expressions(Set<String> ends) { return expressionTokens(takeUntil(ends)); }
        Node expressionTokens(List<Token> input) {
            Expression p = new Expression(input); List<Node> children = new ArrayList<>();
            while (p.pos < input.size()) { int before = p.pos; children.add(p.parse(0, 0)); if (p.pos == before) p.pos++; }
            return new Node("EXPR_LIST", children);
        }
    }

    /** Pratt parsing: operators form AST parents according to binding precedence. */
    private static class Expression {
        final List<Token> ts; int pos;
        Expression(List<Token> ts) { this.ts = ts; }
        String peek() { return pos < ts.size() ? ts.get(pos).text : ""; }
        Node parse(int min, int depth) {
            if (pos >= ts.size()) return new Node("EMPTY");
            if (depth > 128) throw BusinessException.badRequest("表达式嵌套过深");
            Token token = ts.get(pos++); Node left;
            if (token.text.equals("(")) { left = parse(0, depth + 1); if (peek().equals(")")) pos++; }
            else if (Set.of("!", "~", "+", "-", "++", "--", "not", "new", "delete").contains(token.text)) left = new Node("UNARY:" + token.text, parse(13, depth + 1));
            else left = new Node(token.normalized.startsWith("v") && !KEYWORDS.contains(token.text) ? "ID" : token.normalized);
            while (pos < ts.size()) {
                String op = peek();
                if (op.equals("(") || op.equals("[")) {
                    pos++; List<Node> args = new ArrayList<>(); args.add(left); String end = op.equals("(") ? ")" : "]";
                    while (pos < ts.size() && !peek().equals(end)) { args.add(parse(0, depth + 1)); if (peek().equals(",")) pos++; }
                    if (peek().equals(end)) pos++;
                    left = new Node(op.equals("(") ? "CALL" : "INDEX", args); continue;
                }
                if (Set.of("++", "--").contains(op)) { pos++; left = new Node("POSTFIX:" + op, left); continue; }
                if (Set.of(".", "->", "::").contains(op)) { pos++; if (pos < ts.size()) { Token member = ts.get(pos++); left = new Node("MEMBER", left, new Node(member.normalized.startsWith("v") && !KEYWORDS.contains(member.text) ? "ID" : member.normalized)); } else left = new Node("MEMBER", left, new Node("EMPTY")); continue; }
                int precedence = PRECEDENCE.getOrDefault(op, -1);
                if (precedence < min) break;
                pos++; Node right = parse(precedence + (precedence == 1 ? 0 : 1), depth + 1);
                left = new Node((precedence == 1 ? "ASSIGN:" : "BINARY:") + op, left, right);
            }
            return left;
        }
    }
}

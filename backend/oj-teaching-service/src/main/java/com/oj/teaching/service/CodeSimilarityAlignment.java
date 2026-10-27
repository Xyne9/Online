package com.oj.teaching.service;

import org.springframework.stereotype.Component;
import java.util.*;

/** LCS anchors preserve inserted/deleted lines; Hirschberg uses linear auxiliary memory. */
@Component
public class CodeSimilarityAlignment {
    private final CodeSimilarityAnalyzer analyzer;
    public CodeSimilarityAlignment(CodeSimilarityAnalyzer analyzer) { this.analyzer = analyzer; }
    public record Line(Integer aLineNo, String aText, Integer bLineNo, String bText) { }
    public List<Line> align(String codeA, String codeB, String language) {
        String[] a = codeA.split("\\R", -1), b = codeB.split("\\R", -1);
        String[] na = analyzer.normalizedLines(codeA, language);
        String[] nb = analyzer.normalizedLines(codeB, language);
        List<int[]> anchors = new ArrayList<>(); lcs(na, 0, a.length, nb, 0, b.length, anchors);
        List<Line> rows = new ArrayList<>(); int ai = 0, bi = 0;
        for (int[] anchor : anchors) {
            while (ai < anchor[0] || bi < anchor[1]) {
                boolean hasA = ai < anchor[0], hasB = bi < anchor[1];
                rows.add(new Line(hasA ? ai + 1 : null, hasA ? a[ai++] : null, hasB ? bi + 1 : null, hasB ? b[bi++] : null));
            }
            rows.add(new Line(ai + 1, a[ai++], bi + 1, b[bi++]));
        }
        while (ai < a.length || bi < b.length) {
            boolean hasA = ai < a.length, hasB = bi < b.length;
            rows.add(new Line(hasA ? ai + 1 : null, hasA ? a[ai++] : null, hasB ? bi + 1 : null, hasB ? b[bi++] : null));
        }
        return rows;
    }
    private static void lcs(String[] a, int af, int at, String[] b, int bf, int bt, List<int[]> anchors) {
        if (af == at || bf == bt) return;
        if (at - af == 1) { for (int j = bf; j < bt; j++) if (a[af].equals(b[j])) { anchors.add(new int[]{af, j}); break; } return; }
        int mid = (af + at) >>> 1;
        int[] left = lengths(a, af, mid, b, bf, bt, false), right = lengths(a, mid, at, b, bf, bt, true);
        int split = 0, best = -1, count = bt - bf;
        for (int j = 0; j <= count; j++) if (left[j] + right[count - j] > best) { best = left[j] + right[count - j]; split = j; }
        lcs(a, af, mid, b, bf, bf + split, anchors); lcs(a, mid, at, b, bf + split, bt, anchors);
    }
    private static int[] lengths(String[] a, int af, int at, String[] b, int bf, int bt, boolean reverse) {
        int[] previous = new int[bt - bf + 1], current = new int[bt - bf + 1];
        for (int i = 0; i < at - af; i++) {
            current[0] = 0;
            for (int j = 1; j <= bt - bf; j++) current[j] = a[reverse ? at - i - 1 : af + i].equals(b[reverse ? bt - j : bf + j - 1])
                    ? previous[j - 1] + 1 : Math.max(previous[j], current[j - 1]);
            int[] swap = previous; previous = current; current = swap;
        }
        return previous;
    }
}

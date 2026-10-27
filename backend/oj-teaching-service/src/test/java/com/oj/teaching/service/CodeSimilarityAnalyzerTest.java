package com.oj.teaching.service;

import com.oj.common.core.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodeSimilarityAnalyzerTest {
    private final CodeSimilarityAnalyzer analyzer = new CodeSimilarityAnalyzer();
    private static final String CPP = """
            #include <iostream>
            using namespace std;
            int main() {
                int n; cin >> n;
                long sum = 0;
                for (int i=0;i<n;i++) {
                    int value; cin >> value;
                    if (value > 0) sum += value;
                }
                cout << sum;
                return 0;
            }
            """;

    @Test void commentsWhitespaceAndConsistentVariableRenameDisappear() {
        String renamed = CPP.replace("sum", "answer").replace("value", "inputValue").replace("int n;", "int n; /* discarded */")
                .replace("int main()", "// harmless comment\nint main()") + "// final comment";
        var a = analyzer.fingerprint(CPP, "cpp"); var b = analyzer.fingerprint(renamed, "cpp");
        assertEquals(a.normalizedTokens(), b.normalizedTokens());
        assertEquals(100, analyzer.compare(a, b).overall());
    }
    @Test void stringsContainingCommentDelimitersRemainTokens() {
        var code = analyzer.fingerprint("int main(){ string x=\"// not comment /* text */\"; return 0; }", "cpp");
        assertTrue(code.normalizedTokens().contains("LITERAL"));
        assertEquals(1, code.normalizedTokens().stream().filter("LITERAL"::equals).count());
    }
    @Test void weightedResultIsExactlyFortySixty() {
        var a = analyzer.fingerprint(CPP, "cpp");
        var b = analyzer.fingerprint(CPP.replace("sum += value", "sum *= value"), "cpp");
        var result = analyzer.compare(a, b);
        assertEquals(Math.round((.4 * result.simHash() + .6 * result.ast()) * 100) / 100.0, result.overall(), .011);
        assertTrue(result.ast() < 100, "operators must survive in the parsed AST");
    }
    @Test void expressionPrecedenceChangesTheAst() {
        var a = analyzer.fingerprint("int main(){int x=(a+b)*c;return x;}", "cpp");
        var b = analyzer.fingerprint("int main(){int x=a+b*c;return x;}", "cpp");
        assertNotEquals(a.astFeatures(), b.astFeatures());
    }
    @Test void forToWhileObfuscationStillEntersTheSemanticStage() {
        String changed = CPP.replace("for (int i=0;i<n;i++) {", "int i=0; while(i<n) {")
                .replace("if (value > 0) sum += value;", "if (value > 0) sum += value; i++;")
                .replace("sum", "answer").replace("value", "input");
        assertTrue(analyzer.compare(analyzer.fingerprint(CPP,"cpp"), analyzer.fingerprint(changed,"cpp")).overall() >= 75);
    }
    @Test void javaAndPythonStatementsProduceNestedSyntaxNodes() {
        var java = analyzer.fingerprint("class Main { public static void main(String[] args) { int total=0; while(total<10){total++;} } }", "java");
        var python = analyzer.fingerprint("def solve():\n    total = 0\n    for item in range(10):\n        if item > 0:\n            total += item\n    return total\n", "python");
        assertTrue(java.astFeatures().keySet().stream().anyMatch(s -> s.contains("LOOP")));
        assertTrue(python.astFeatures().keySet().stream().anyMatch(s -> s.contains("IF")));
        assertEquals(100, analyzer.compare(python, analyzer.fingerprint("def solve():\n    answer = 0\n    for element in range(10):\n        if element > 0:\n            answer += element\n    return answer\n", "python")).overall());
    }
    @Test void malformedBlocksFailInsteadOfInventingFingerprints() {
        assertThrows(BusinessException.class, () -> analyzer.fingerprint("int main(){ return 1;", "cpp"));
        assertThrows(BusinessException.class, () -> analyzer.fingerprint("int main(){ /* never closed", "cpp"));
    }
    @Test void fiftySamplesAnd1225PairsReuseFingerprints() {
        List<CodeSimilarityAnalyzer.Fingerprint> fingerprints = new ArrayList<>();
        long start = System.nanoTime();
        for (int i=0;i<50;i++) fingerprints.add(analyzer.fingerprint(CPP.replace("sum", "answer" + i), "cpp"));
        int compared=0;
        for (int i=0;i<50;i++) for (int j=i+1;j<50;j++) { assertEquals(100, analyzer.compare(fingerprints.get(i),fingerprints.get(j)).overall()); compared++; }
        assertEquals(1225,compared);
        assertTrue((System.nanoTime()-start)/1_000_000 < 2000,"AC-3.3: 1225 coarse comparisons must finish within 2s");
    }
}

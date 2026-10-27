package com.oj.teaching.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeSimilarityAlignmentTest {
    private final CodeSimilarityAlignment alignment = new CodeSimilarityAlignment(new CodeSimilarityAnalyzer());
    @Test void insertionDoesNotShiftAllFollowingRows() {
        var rows = alignment.align("int main(){\nint a=1;\nreturn a;\n}", "int main(){\nint b=1;\nb++;\nreturn b;\n}", "cpp");
        var inserted = rows.stream().filter(row -> "b++;".equals(row.bText())).findFirst().orElseThrow();
        assertNull(inserted.aLineNo());
        var returned = rows.stream().filter(row -> "return a;".equals(row.aText())).findFirst().orElseThrow();
        assertEquals(3,returned.aLineNo()); assertEquals(4,returned.bLineNo());
        assertEquals("return b;", returned.bText());
    }
    @Test void deletedLineAppearsOnOneSideOnly() {
        var rows = alignment.align("x=1\nx+=2\nprint(x)", "y=1\nprint(y)", "python");
        var removed = rows.stream().filter(row -> "x+=2".equals(row.aText())).findFirst().orElseThrow();
        assertNull(removed.bLineNo()); assertNull(removed.bText());
        assertEquals(3, rows.get(rows.size()-1).aLineNo()); assertEquals(2, rows.get(rows.size()-1).bLineNo());
    }
    @Test void multilineCommentsAreLexedAsAWholeSource() {
        var rows = alignment.align("int main(){\n/* note\ncontinued */\nreturn 0;\n}","int main(){\nreturn 0;\n}","cpp");
        var returned=rows.stream().filter(row -> "return 0;".equals(row.aText())).findFirst().orElseThrow();
        assertEquals(4,returned.aLineNo()); assertEquals(2,returned.bLineNo());
    }
}

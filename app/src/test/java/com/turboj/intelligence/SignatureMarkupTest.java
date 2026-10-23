package com.turboj.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignatureMarkupTest {
    @Test
    void genericCommasDoNotAdvanceParameterHighlight() {
        String html =
                IntelligenceController.parameterMarkup(
                        "void f(Map<String, Integer> values, int count)", 1);
        assertTrue(html.contains("<b><u> int count</u></b>"));
        assertTrue(html.contains("Map&lt;String, Integer&gt;"));
    }

    @Test
    void varargsRemainHighlightedForAdditionalArguments() {
        String html =
                IntelligenceController.parameterMarkup("printf(String format, Object... args)", 3);
        assertTrue(html.contains("<b><u> Object... args</u></b>"));
    }
}

package com.turboj.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NavigationHistoryTest {
    private record Visit(
            String file, int caret, int anchor, int topLine, int leftColumn, long version) {}

    @Test
    void threeFilesRestoreSelectionsAndViewportsInBothDirections() {
        var history = new NavigationHistory<Visit>();
        var main = new Visit("Main.java", 57, 54, 2, 3, 1);
        var calculator = new Visit("Calculator.java", 101, 101, 8, 0, 3);
        var math = new Visit("MathUtils.java", 214, 209, 15, 4, 8);
        history.jumpedFrom(main);
        history.jumpedFrom(calculator);
        assertEquals(calculator, history.back(math));
        assertEquals(main, history.back(calculator));
        assertNull(history.back(main));
        assertEquals(calculator, history.forward(main));
        assertEquals(math, history.forward(calculator));
        assertNull(history.forward(math));
    }

    @Test
    void editsDoNotResetHistoryAndForwardCapturesTheUpdatedLocation() {
        var history = new NavigationHistory<Visit>();
        var main = new Visit("Main.java", 57, 54, 2, 3, 1);
        var calculator = new Visit("Calculator.java", 101, 101, 8, 0, 3);
        var math = new Visit("MathUtils.java", 214, 209, 15, 4, 8);
        history.jumpedFrom(main);
        history.jumpedFrom(calculator);
        assertEquals(calculator, history.back(math));
        var editedCalculator = new Visit("Calculator.java", 120, 120, 9, 0, 4);
        assertEquals(main, history.back(editedCalculator));
        assertEquals(editedCalculator, history.forward(main));
        assertEquals(math, history.forward(editedCalculator));
    }

    @Test
    void saveAsRemapsBackwardAndForwardPathsWithoutLosingLocations() {
        var history = new NavigationHistory<Visit>();
        var old = new Visit("Untitled.java", 57, 54, 2, 3, 1);
        var calculator = new Visit("Calculator.java", 101, 101, 8, 0, 3);
        var laterOld = new Visit("Untitled.java", 214, 209, 15, 4, 8);
        history.jumpedFrom(old);
        history.jumpedFrom(calculator);
        assertEquals(calculator, history.back(laterOld));
        history.remap(
                visit ->
                        visit.file().equals("Untitled.java")
                                ? new Visit(
                                        "Main.java",
                                        visit.caret(),
                                        visit.anchor(),
                                        visit.topLine(),
                                        visit.leftColumn(),
                                        visit.version())
                                : visit);
        Visit main = history.back(calculator);
        assertEquals(new Visit("Main.java", 57, 54, 2, 3, 1), main);
        assertEquals(calculator, history.forward(main));
        assertEquals(new Visit("Main.java", 214, 209, 15, 4, 8), history.forward(calculator));
    }

    @Test
    void newJumpsDiscardForwardHistoryAndHistoryIsBounded() {
        var history = new NavigationHistory<Integer>();
        history.jumpedFrom(1);
        history.jumpedFrom(2);
        assertEquals(2, history.back(3));
        history.jumpedFrom(2);
        assertNull(history.forward(4));
        assertEquals(2, history.back(4));
        for (int i = 0; i < 300; i++) history.jumpedFrom(i);
        int count = 0;
        Integer current = 300;
        while ((current = history.back(current)) != null) count++;
        assertEquals(256, count);
    }
}

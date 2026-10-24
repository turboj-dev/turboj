package dev.turboj.examples.calculator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculatorTest {
    @Test
    void addsPositiveAndNegativeNumbers() {
        assertEquals(7, Calculator.add(3, 4));
        assertEquals(-1, Calculator.add(3, -4));
    }

    @Test
    void dividesWithoutTruncationAndRejectsZero() {
        assertEquals(3.5, Calculator.divide(7, 2));
        assertThrows(IllegalArgumentException.class, () -> Calculator.divide(7, 0));
    }
}

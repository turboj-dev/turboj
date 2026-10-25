package tutorial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class CalculatorTest {
    @Test void testCalculatorAddition() {
        assertEquals(42, new Calculator().addition(20, 22));
    }
}

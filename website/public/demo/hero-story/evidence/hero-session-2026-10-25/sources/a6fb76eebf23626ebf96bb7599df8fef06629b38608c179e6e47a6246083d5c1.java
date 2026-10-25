import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceTest {
    @Test void appliesTwentyPercentDiscount() {
        assertEquals(800, Price.discount(1000, 20));
    }

    @Test void keepsFullPriceWithoutDiscount() {
        assertEquals(1000, Price.discount(1000, 0));
    }

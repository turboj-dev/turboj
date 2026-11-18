package demo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.cherrytest.junit.CherryTest;
import com.cherrytest.junit.TestEnv;
class PriceTest {
    @CherryTest(scope="none")
    void demo_project_price_suite_discount_test(TestEnv env) throws Exception {
        Thread.sleep(250);
        assertEquals(90, Price.discounted(100, 10));
    }
    @CherryTest(scope="none")
    void demo_project_price_suite_fullprice_test(TestEnv env) throws Exception {
        Thread.sleep(250);
        assertEquals(100, Price.discounted(100, 0));
    }
}

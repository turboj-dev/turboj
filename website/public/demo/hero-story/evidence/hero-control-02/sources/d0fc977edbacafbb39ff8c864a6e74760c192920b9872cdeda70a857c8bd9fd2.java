public class Price {
    public static int discount(int cents, int percent) {
        int reduction = cents * percent / 100;
        return cents - reduction;
    }


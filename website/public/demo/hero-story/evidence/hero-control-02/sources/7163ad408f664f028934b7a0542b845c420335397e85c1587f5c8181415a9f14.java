public class Price {
    public static int discountedPrice(int cents, int percent) {
        int reduction = cents * percent / 100;
        return cents - reduction;
    }

    public static void main(String[] args) {
        System.out.println("Price: " + discountedPrice(1000, 20) + " cents");
    }
}

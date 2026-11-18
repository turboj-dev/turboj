package demo;

public final class Price {
    private Price() {}

    public static int discounted(int cents, int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent outside 0..100");
        }
        return cents - cents * percent / 100;
    }

    public static int deliveryFee() {
        return 500;
    }

    public static void main(String[] args) {
        System.out.println(discounted(100, 10));
    }
}

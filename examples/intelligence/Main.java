/** Small cross-file navigation fixture. Open this file directly in TurboJ. */
public class Main {
    public static void main(String[] args) {
        Calculator calculator = new Calculator();
        int result = calculator.add(2, 3);
        String joined = calculator.add("Turbo", "J");
        System.out.println(joined + ": " + result);
        System.out.println(joined.substring(1));
    }
}

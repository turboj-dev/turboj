package dev.turboj.examples.calculator;

public final class Calculator {
    private Calculator() {}

    public static int add(int first, int second) {
        return first + second;
    }

    public static double divide(int dividend, int divisor) {
        if (divisor == 0) {
            throw new IllegalArgumentException("Cannot divide by zero");
        }
        return (double) dividend / divisor;
    }

    public static void main(String[] args) {
        System.out.println("3 + 4 = " + add(3, 4));
        System.out.println("7 / 2 = " + divide(7, 2));
    }
}

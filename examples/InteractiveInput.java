import java.util.Scanner;

public class InteractiveInput {
    public static void main(String[] args) {
        System.out.print("Name: ");
        String name = new Scanner(System.in).nextLine();
        System.out.println("Hello " + name);
    }
}

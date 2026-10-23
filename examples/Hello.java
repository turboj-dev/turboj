import java.util.Scanner;
public class Hello {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int n = -1;
        System.out.print("Inserire un numero: ");
        while(!scanner.hasNextInt() && (n = scanner.nextInt()) < 0 && n > 100) {
            System.out.print("Numero non valido. Inserire un numero: ");
        }
        System.out.println("Il numero inserito è: " + n);
        scanner.close();
    }
}
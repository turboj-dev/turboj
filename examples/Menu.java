import java.util.Scanner;

public class Menu {
    public static void main(String[] args) {
        System.out.print("Quanti voti vuoi inserire? ");
        Scanner scanner = new Scanner(System.in);
        int n = 0;
        while(!scanner.hasNextInt() || (n = scanner.nextInt()) < 1) {
            System.out.print("Valore non valido. Quanti voti vuoi inserire? ");
            scanner.nextLine();
        }
        float[] voti = new float[n];
        for(int i = 0; i < n; i++) {
            System.out.print("Inserisci il voto: ");
            float voto = 0;
            while(!scanner.hasNextFloat() || (voto = scanner.nextFloat()) < 0) {
                System.out.print("Valore non valido. Inserisci il voto? ");
                scanner.nextLine();
            }
            voti[i] = voto;
        }

        float totale = 0;
        for(int i = 0; i < n; totale += voti[i++]);
        System.out.println("Totale note: " + totale);

        System.out.print("Media: ");
        float media = totale / n;

        System.out.println(media);
        System.out.println("Note positive: ");
        for(int i = 0; i < n; i++) {
            if(voti[i] >= 4) System.out.println(voti[i]);
        }
        for(float voto : voti) {
            if(voto >= 4)
                System.out.println(voto);
        }
        scanner.close();
    }
}

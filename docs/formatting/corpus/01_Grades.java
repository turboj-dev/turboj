import java.util.Scanner;

public class Grades {
public static void main(String[] args) {
Scanner input = new Scanner(System.in);
System.out.print("How many grades? ");
int count = input.nextInt();
double total = 0; int failed = 0;
for (int i = 1; i <= count; i++) {
System.out.print("Grade " + i + ": ");
double grade = input.nextDouble();
if (grade < 0 || grade > 10) { System.out.println("Grades go from 0 to 10, try again"); i--; continue; }
if (grade < 6) failed++;
total += grade;
}
while (count == 0) { System.out.println("No grades entered"); break; }
System.out.println("Average: " + (count == 0 ? 0 : total / count) + ", failed: " + failed + " of " + count + " grades entered");
}
}

// A compact source file (Java 25): no class declaration, an instance main.
final String greeting = "Hello";

String greet(String name) { return greeting + ", " + name + "!"; }

void main() {
var names = java.util.List.of("Ada", "Grace", "Linus");
for (String name : names) IO.println(greet(name));
IO.println("Greeted " + names.size() + " people at " + java.time.LocalTime.now().withNano(0) + " using a compact source file");
}

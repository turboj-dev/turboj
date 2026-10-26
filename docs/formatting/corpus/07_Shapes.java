public class Shapes {
sealed interface Shape permits Circle, Rectangle, Triangle {}
record Circle(double radius) implements Shape {}
record Rectangle(double width, double height) implements Shape {}
record Triangle(double base, double height) implements Shape {}
record Labeled(String label, Shape shape) {}

static double area(Shape shape) {
return switch (shape) {
case Circle c -> Math.PI * c.radius() * c.radius();
case Rectangle(double w, double h) when w == h -> w * w;
case Rectangle(double w, double h) -> w * h;
case Triangle(var base, var height) -> base * height / 2;
};
}

static String describe(Object value) {
if (value instanceof Labeled(String label, Circle(double r)) && r > 0) return label + " is a circle of radius " + r;
return switch (value) { case null -> "nothing"; case Labeled l -> l.label() + ": " + area(l.shape()); case Shape s -> "area " + area(s); default -> value.toString(); };
}
}

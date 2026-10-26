import java.util.List;

public class Shapes {

    public sealed interface Shape permits Circle, Square, Rectangle {
    }

    public record Circle(double radius) implements Shape {
    }

    public record Square(double side) implements Shape {
    }

    public record Rectangle(double width, double height) implements Shape {
    }

    public static double area(Shape shape) {
        return switch (shape) {
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Square s -> s.side() * s.side();
            /*|*/
        };
    }

    public static double totalArea(List<Shape> shapes) {
        return shapes.stream().mapToDouble(Shapes::area).sum();
    }
}

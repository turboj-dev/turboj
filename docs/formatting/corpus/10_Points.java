import module java.base;

public class Points {
static class Point {
final double x, y;
Point(double x, double y) { this.x = x; this.y = y; }
}

// Java 25 flexible constructor bodies: statements before super(...).
static class PositivePoint extends Point {
PositivePoint(double x, double y) {
if (x < 0 || y < 0) throw new IllegalArgumentException("Coordinates must be positive: (" + x + ", " + y + ")");
var scale = Math.max(1, Math.hypot(x, y) / 100);
super(x / scale, y / scale);
}
}

public static void main(String[] args) {
List<Point> points = Stream.of(new PositivePoint(3, 4), new PositivePoint(300, 400)).map(Point.class::cast).toList();
points.forEach(p -> IO.println(p.x + ", " + p.y));
}
}

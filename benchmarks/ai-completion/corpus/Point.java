public record Point(double x, double y) {

    public static final Point ORIGIN = new Point(0, 0);

    public double distanceTo(Point other) {
        double dx = x - other.x();
        double dy = /*|*/;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public Point translate(double deltaX, double deltaY) {
        return new Point(x + deltaX, y + deltaY);
    }

    public double magnitude() {
        return distanceTo(ORIGIN);
    }
}

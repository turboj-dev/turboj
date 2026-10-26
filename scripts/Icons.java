import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The TurboJ icon, drawn from one geometry: a cyan T and a yellow J on blue, on a 1254-unit square. Writes the SVG
 * (branding and favicon), the full-bleed PNG, the app's window and Dock images, and a macOS iconset for
 * {@code iconutil}. Run through scripts/icons.sh: {@code java scripts/Icons.java <repository root> <iconset dir>}.
 */
public final class Icons {
    static final int UNITS = 1254;
    static final Color BLUE = new Color(0x0000FF), CYAN = new Color(0x00FFFF), YELLOW = new Color(0xFFFF00);
    /** Outlines in icon units, as SVG path data; the same points build the Java2D shapes. */
    static final String T = "M134 292H649V439H482V973H299V439H134Z";
    static final String J = "M736 292H1120V900L1043 973H703L630 900V743H801V828H937V439H736Z";
    static final int[] APP_SIZES = {16, 32, 48, 64, 128, 256, 512, 1024};

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path iconset = Path.of(args.length > 1 ? args[1] : "target/TurboJ.iconset");
        String svg = svg();
        write(root.resolve("branding/turboj-icon.svg"), svg);
        write(root.resolve("website/public/favicon.svg"), svg);
        png(square(1024), root.resolve("branding/turboj-icon.png"));
        Path resources = root.resolve("app/src/main/resources/dev/turboj/app/icons");
        for (int size : APP_SIZES) png(app(size), resources.resolve("turboj-" + size + ".png"));
        // macOS iconset: each point size at 1x and 2x.
        for (int size : new int[] {16, 32, 128, 256, 512}) {
            png(app(size), iconset.resolve("icon_" + size + "x" + size + ".png"));
            png(app(size * 2), iconset.resolve("icon_" + size + "x" + size + "@2x.png"));
        }
    }

    static String svg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + UNITS + " " + UNITS + "\">"
                + "<rect width=\"" + UNITS + "\" height=\"" + UNITS + "\" fill=\"" + hex(BLUE) + "\"/>"
                + "<path fill=\"" + hex(CYAN) + "\" d=\"" + T + "\"/>"
                + "<path fill=\"" + hex(YELLOW) + "\" d=\"" + J + "\"/></svg>\n";
    }

    /** The design edge to edge, for the web and the repository. */
    static BufferedImage square(int size) {
        return draw(size, new java.awt.Rectangle(0, 0, size, size), 0);
    }

    /**
     * The design on Apple's app icon grid: an 824/1024 rounded square, centred, so the Dock shows it at the size and
     * with the corners of every other app.
     */
    static BufferedImage app(int size) {
        double body = size * 824.0 / 1024, inset = (size - body) / 2;
        return draw(size, new java.awt.geom.Rectangle2D.Double(inset, inset, body, body), body * 185.4 / 824);
    }

    private static BufferedImage draw(int size, java.awt.geom.Rectangle2D body, double radius) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(BLUE);
        g.fill(radius == 0 ? body : new RoundRectangle2D.Double(body.getX(), body.getY(), body.getWidth(), body.getHeight(), radius * 2, radius * 2));
        var scale = new AffineTransform();
        scale.translate(body.getX(), body.getY());
        scale.scale(body.getWidth() / UNITS, body.getHeight() / UNITS);
        g.setColor(CYAN);
        g.fill(scale.createTransformedShape(shape(T)));
        g.setColor(YELLOW);
        g.fill(scale.createTransformedShape(shape(J)));
        g.dispose();
        return image;
    }

    /** The subset of SVG path data used above: M, H, V, L, Z with absolute coordinates. */
    static Shape shape(String data) {
        var path = new Path2D.Double();
        double x = 0, y = 0;
        var tokens = data.replaceAll("([MHVLZ])", " $1 ").trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "M" -> { x = Double.parseDouble(tokens[++i]); y = Double.parseDouble(tokens[++i]); path.moveTo(x, y); }
                case "L" -> { x = Double.parseDouble(tokens[++i]); y = Double.parseDouble(tokens[++i]); path.lineTo(x, y); }
                case "H" -> { x = Double.parseDouble(tokens[++i]); path.lineTo(x, y); }
                case "V" -> { y = Double.parseDouble(tokens[++i]); path.lineTo(x, y); }
                case "Z" -> path.closePath();
                default -> throw new IllegalArgumentException("Unsupported path token " + tokens[i]);
            }
        }
        return path;
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    private static void png(BufferedImage image, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}

package dev.turboj.ui;

import dev.turboj.testing.intelligence.LineIntelligence.*;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Real editor rendering with explicitly synthetic states, for reproducible visual review. */
public final class CoverageVisualPreview {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "app/target/coverage-visual-preview" : args[0]);
        Files.createDirectories(output);
        for (String id : List.of("vscode-monokai","light")) {
            SwingUtilities.invokeAndWait(() -> {
                try { ImageIO.write(render(id,false),"png",output.resolve(id + ".png").toFile()); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            try { ImageIO.write(render("vscode-monokai",true),"png",output.resolve("ascii-fallback.png").toFile()); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        });
        paletteGallery(output);
    }

    private static void paletteGallery(Path output) throws java.io.IOException {
        var ids = Theme.bundledResources().keySet().stream().sorted().toList();
        var audit = new java.util.ArrayList<Map<String,Object>>();
        for (int page = 0; page * 24 < ids.size(); page++) {
            BufferedImage image = new BufferedImage(1280,772,BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.DARK_GRAY); g.fillRect(0,0,image.getWidth(),image.getHeight());
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,16)); g.setColor(Color.WHITE);
            g.drawString("Coverage palette review - synthetic swatches - page " + (page + 1),12,25);
            for (int item = 0; item < 24 && page * 24 + item < ids.size(); item++) {
                Theme theme = Theme.bundled(ids.get(page * 24 + item));
                var palette = dev.turboj.ui.intelligence.CoverageRowPalette.resolve(theme);
                int x = item % 4 * 320, y = 40 + item / 4 * 122;
                g.setColor(theme.background()); g.fillRect(x + 2,y,316,118);
                g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12)); g.setColor(theme.foreground());
                g.drawString(theme.id(),x + 10,y + 18);
                g.drawString("Normal    int n = 1;",x + 10,y + 40);
                g.setColor(palette.covered()); g.fillRect(x + 8,y + 49,300,25);
                g.setColor(palette.ink(theme.foreground(),false)); g.drawString("Covered   int n = 1;",x + 10,y + 66);
                g.setColor(palette.uncovered()); g.fillRect(x + 8,y + 81,300,25);
                g.setColor(palette.ink(theme.foreground(),true)); g.drawString("Uncovered int n = 1;",x + 10,y + 98);
                var values = new java.util.LinkedHashMap<String,Object>();
                values.put("theme",theme.id()); values.put("background",hex(theme.background()));
                values.put("coveredBackground",hex(palette.covered())); values.put("uncoveredBackground",hex(palette.uncovered()));
                var inks = new java.util.LinkedHashMap<String,Object>();
                theme.colors().entrySet().stream().filter(entry -> entry.getKey().startsWith("syntax.")).sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> inks.put(entry.getKey(),Map.of("normal",hex(entry.getValue()),
                                "covered",hex(palette.ink(entry.getValue(),false)),"uncovered",hex(palette.ink(entry.getValue(),true)))));
                values.put("syntaxInks",inks); audit.add(values);
            }
            g.dispose(); ImageIO.write(image,"png",output.resolve("themes-" + (page + 1) + ".png").toFile());
        }
        Files.writeString(output.resolve("theme-palettes.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(audit) + "\n");
    }

    private static String hex(Color color) { return String.format("#%06X",color.getRGB() & 0xffffff); }

    private static BufferedImage render(String id,boolean ascii) {
        Theme theme = Theme.bundled(id);
        EditorComponent editor = new EditorComponent(); editor.setTheme(theme); editor.setFontSize(18);
        if (ascii) editor.setFont(new Font(Font.MONOSPACED,Font.PLAIN,18) {
            @Override public boolean canDisplay(char c) { return c < 128; }
        });
        editor.setSize(1060,400);
        editor.setText("class CoveragePreview {\n"
                + "    // Synthetic rendering fixture; not execution evidence.\n"
                + "    void example() {\n"
                + "        validate();       // covered, associated test passed\n"
                + "        verifyRisk();     // covered, associated test FAILED\n"
                + "        checkBranch();    // partial instruction / branch coverage\n"
                + "        audit();          // measured uncovered; test association unknown\n"
                + "        unknown();        // unverified / unavailable evidence\n"
                + "        previous();       // stale evidence\n"
                + "    }\n"
                + "}\n");
        SourceIdentity source = new SourceIdentity(null,null,"preview","preview",Path.of("CoveragePreview.java"),
                "preview",editor.version(),"synthetic","synthetic");
        editor.setLineIntelligence(new Snapshot(source,Map.of(
                4,line(false,TestStatus.PASSED,CoverageState.COVERED,Freshness.CURRENT),
                5,line(true,TestStatus.FAILED,CoverageState.COVERED,Freshness.CURRENT),
                6,line(true,null,CoverageState.PARTIAL,Freshness.CURRENT),
                7,line(true,null,CoverageState.UNCOVERED,Freshness.CURRENT),
                8,line(false,null,CoverageState.UNKNOWN,Freshness.UNKNOWN),
                9,line(false,TestStatus.FAILED,CoverageState.COVERED,Freshness.STALE)),List.of(),true,true,"HEAD","Synthetic preview"));
        int selected = editor.getText().indexOf("verifyRisk"); editor.setSelection(selected,selected + "verifyRisk".length());
        BufferedImage image = new BufferedImage(1060,470,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(theme.background()); g.fillRect(0,0,image.getWidth(),image.getHeight());
        g.setFont(editor.getFont()); g.setColor(theme.foreground());
        g.drawString(theme.name() + (ascii ? " / ASCII fallback" : "") + " - SYNTHETIC visual review",16,26);
        g.translate(0,40); editor.paint(g); g.translate(0,-40);
        g.setColor(theme.foreground());
        g.drawString("Left: Git | Tests. Right: Coverage. Shading describes coverage only.",16,452);
        g.dispose(); return image;
    }

    private static Line line(boolean added,TestStatus status,CoverageState state,Freshness freshness) {
        List<TestResult> tests = status == null ? List.of() : List.of(new TestResult("fixture","preview","suite","fixture",
                "preview","preview","","unit","synthetic","1",status,freshness,0,"Synthetic",List.of()));
        Coverage coverage = new Coverage(state,freshness,Availability.AVAILABLE,Precision.INSTRUCTIONS_AND_BRANCHES,
                state == CoverageState.UNCOVERED ? 0 : 4,state == CoverageState.COVERED ? 0 : 2,
                state == CoverageState.UNCOVERED ? 0 : 1,state == CoverageState.COVERED ? 0 : 1,
                "synthetic","preview","preview","synthetic","synthetic","synthetic","Synthetic visual fixture");
        return new Line(added,tests,coverage);
    }
}

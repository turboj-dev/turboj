package dev.turboj.benchmarks;

/** Reproducible workloads with executable code, rather than comment padding. */
public final class Workloads {
    private Workloads() {}

    public static String hello(long revision) {
        return "public class Hello {\n public static void main(String[] args) {\n"
                + "  System.out.println(\"Hello TurboJ! "
                + revision
                + "\");\n }\n}\n";
    }

    public static String source(int lines, long revision) {
        if (lines == 0) return "";
        if (lines < 20) return hello(revision);
        StringBuilder source = new StringBuilder(lines * 20);
        source.append("class Workload {\n // editing here\n static final int EDIT = ")
                .append(revision)
                .append(";\n");
        int remaining = lines - 4, method = 0;
        while (remaining > 3) {
            int statements = Math.min(remaining - 3, 100);
            source.append(" static int f").append(method++).append("() {\n int x = 0;\n");
            for (int i = 0; i < statements; i++) source.append(" x += ").append(i).append(";\n");
            source.append(" return x; }\n");
            remaining -= statements + 3;
        }
        while (remaining-- > 0) source.append("\n");
        return source.append("}\n").toString();
    }
}

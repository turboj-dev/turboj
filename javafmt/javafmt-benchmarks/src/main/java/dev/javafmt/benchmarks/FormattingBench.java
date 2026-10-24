package dev.javafmt.benchmarks;

import dev.javafmt.core.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class FormattingBench {
    @Param({"1024", "10240", "102400"})
    public int bytes;
    private JavaFormatter formatter;
    private FormatRequest request, canonical;
    private TextRange range;

    public static String source(int bytes) {
        StringBuilder result = new StringBuilder("public class Workload {");

        for (int i = 0; result.length() < bytes; i++)
            result.append("int method").append(i)
                    .append("(int value){int doubled=value*2;if(doubled>100){return doubled-1;}return doubled+1;}");
        return result.append('}').toString();
    }

    @Setup
    public void setup() {
        formatter = new JavaFormatter();
        request = new FormatRequest(source(bytes));
        canonical = new FormatRequest(formatter.format(request.source()));
        int offset = request.source().indexOf("doubled=value");
        range = new TextRange(offset, offset + 2);
    }

    @Benchmark
    public FormatResult wholeFile() {
        return checked(formatter.format(request));
    }

    @Benchmark
    public FormatResult alreadyFormatted() {
        return checked(formatter.format(canonical));
    }

    @Benchmark
    public FormatResult statementRange() {
        return checked(formatter.formatRange(request, range));
    }

    private FormatResult checked(FormatResult result) {
        if (!result.success())
            throw new IllegalStateException(result.diagnostics().toString());
        return result;
    }
}

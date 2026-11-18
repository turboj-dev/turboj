package dev.turboj.app;

import com.google.gson.GsonBuilder;
import dev.turboj.config.Settings;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import static dev.turboj.testing.intelligence.LineIntelligence.sha256;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Full application synthetic input-to-paint, scroll and dense-decoration probe; no hardware claim. */
public final class ProductPerformanceProbe {
    static TurboFrame frame;
    static <T>T edt(Callable<T> action)throws Exception{var t=new FutureTask<T>(action);SwingUtilities.invokeAndWait(t);return t.get();}
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]), fixture=Files.createTempDirectory("turboj-performance-");
        System.setProperty("turboj.config",fixture.resolve("config").toString());
        var results=new LinkedHashMap<String,Object>();
        results.put("java",System.getProperty("java.runtime.version"));
        results.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        results.put("method","Fresh JVM with warm OS/dependency caches. Full TurboFrame; posted synthetic keys, handler-to-Java2D-paint completion. Excludes physical hardware and compositor presentation. Dense coverage/test data is a rendering fixture, not execution evidence.");
        long start=System.nanoTime();
        try{
            frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();f.editor().requestFocusInWindow();return f;});
            edt(()->{frame.getRootPane().paintImmediately(0,0,frame.getWidth(),frame.getHeight());return null;});
            results.put("windowVisibleMs",(System.nanoTime()-start)/1e6);
            var cases=new ArrayList<Map<String,Object>>();
            for(int lines:new int[]{1000,10000}){
                String source=source(lines);Path file=fixture.resolve("lines-"+lines).resolve("Workload.java");Files.createDirectories(file.getParent());Files.writeString(file,source);
                edt(()->{frame.openPath(file);return null;});
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(!edt(()->file.equals(frame.currentFile())&&frame.editor().getText().equals(source))){if(System.nanoTime()>until)throw new AssertionError("File not open");Thread.sleep(10);}
                edt(()->frame.compile(false,false)).get(30,TimeUnit.SECONDS);
                Thread.sleep(500);
                for(boolean dense:new boolean[]{false,true}){
                    edt(()->{
                        var e=frame.editor();e.gotoLine(2);e.gotoOffset(e.document().lineEnd(1));
                        if(dense) decorate(file, fixture); else e.setLineIntelligence(null);
                        e.resetInputToPaintSamples();return null;
                    });
                    for(int n=0;n<120;n++){
                        edt(()->{KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(frame.editor(),new KeyEvent(frame.editor(),KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,'x'));return null;});
                        Thread.sleep(15);
                    }
                    Thread.sleep(250);
                    long[] samples=edt(()->frame.editor().inputToPaintSamples());
                    if(samples.length==0)throw new AssertionError("No painted input");
                    if(dense) edt(()->{decorate(file, fixture);return null;});
                    long[] jumps=new long[100];
                    for(int n=0;n<100;n++){final int line=(n%2==0?lines-10:2);jumps[n]=edt(()->{long s=System.nanoTime();frame.editor().gotoLine(line);frame.editor().paintImmediately(0,0,frame.editor().getWidth(),frame.editor().getHeight());return System.nanoTime()-s;});}
                    if(dense && edt(()->frame.editor().lineIntelligence()==null))throw new AssertionError("Dense scroll snapshot disappeared");
                    var row=new LinkedHashMap<String,Object>();row.put("lines",lines);row.put("denseScroll",dense);row.put("typingDecoration",dense ? "Dense snapshot invalidates on the first source edit, as required" : "No decorations");row.put("keys",120);row.put("painted",samples.length);row.put("handlerToPaintMs",stats(samples));row.put("jumpAndPaintMs",stats(jumps));row.put("handlerToPaintNanos",samples);row.put("jumpAndPaintNanos",jumps);cases.add(row);
                }
            }
            results.put("cases",cases);results.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        }finally{
            Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(results)+"\n");
            if(frame!=null)edt(()->{frame.shutdown();frame.dispose();return null;});
        }
        System.out.println(output);System.exit(0);
    }
    static void decorate(Path file,Path fixture){
        var e=frame.editor();String hash=sha256(e.getText());
        var id=new SourceIdentity(fixture,null,"fixture","",file.toAbsolutePath(),"synthetic",e.version(),hash,"render-only");
        var pass=new TestResult("r","t","fixture","fixture","fixture","","","","","",TestStatus.PASSED,Freshness.CURRENT,0,"",List.of());
        var c=new Coverage(CoverageState.COVERED,Freshness.CURRENT,Availability.AVAILABLE,Precision.BINARY_LINE,-1,-1,-1,-1,"render-only","synthetic","r","",hash,"","");
        var rows=new HashMap<Integer,Line>();for(int n=1;n<=e.document().lineCount();n++)rows.put(n,new Line(true,List.of(pass),c));
        if(!e.setLineIntelligence(new Snapshot(id,rows,List.of(),true,true,"HEAD","Synthetic render fixture")))throw new AssertionError("Snapshot rejected");
    }
    static Map<String,Double>stats(long[] values){long[] v=values.clone();Arrays.sort(v);return Map.of("p50",v[v.length/2]/1e6,"p95",v[(int)Math.ceil(v.length*.95)-1]/1e6,"max",v[v.length-1]/1e6);}
    static String source(int lines){var s=new StringBuilder("class Workload {\n // typing\n");for(int i=0;i<lines/20;i++){s.append(" static int f").append(i).append("(){ int x=0;\n");for(int j=0;j<18;j++)s.append(" x += ").append(j).append(";\n");s.append(" return x; }\n");}return s.append("}\n").toString();}
}

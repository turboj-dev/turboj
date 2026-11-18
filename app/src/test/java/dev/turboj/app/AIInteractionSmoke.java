package dev.turboj.app;

import dev.turboj.ai.*;
import dev.turboj.intelligence.CompletionCoordinator;
import dev.turboj.ui.EditorComponent;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Real focused Swing keys and inference scheduling; deterministic provider, not model quality. */
public final class AIInteractionSmoke {
    static final String SOURCE = "class Example { int square(int value) {\n    \n} }";
    static final StringBuilder report = new StringBuilder();
    static JFrame frame; static EditorComponent editor; static AICompletionController ai;
    static final AtomicInteger calls = new AtomicInteger();
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/ai-interaction.txt");
        try {
            edt(() -> {
                frame = new JFrame("TurboJ AI typing qualification"); editor = new EditorComponent();
                editor.setText(SOURCE); editor.gotoOffset(SOURCE.indexOf("    ")+4);
                var d = AIConfig.defaults();
                var config = new AIConfig(AIConfig.Mode.LOCAL,d.localBackend(),d.localEndpoint(),d.localModel(),d.cloudEndpoint(),d.cloudModel(),true,false,false,200,3,256,d.contextChars(),d.exclusions(),true);
                var coordinator = new CompletionCoordinator(editor);
                ai = new AICompletionController(editor,coordinator,()->new AICompletionController.Snapshot(Path.of("Example.java"),editor.snapshot()),config,c->new AICompletionProvider() {
                    public void stream(AICompletionRequest request, Consumer<String> result, Cancellation token) {
                        calls.incrementAndGet(); result.accept("turn value * value;");
                    }
                    public Availability check(Cancellation token) { return new Availability(true,"controlled native fixture"); }
                    public void close() {}
                },ignored->{},ignored->{});
                Runnable changed = () -> { coordinator.clear(); ai.changed(); };
                editor.setOnChange(changed); editor.setOnCaretChange(changed); editor.setKeyHandler(ai::handleKey);
                frame.add(editor);frame.setSize(900,500);frame.setVisible(true);frame.toFront();Desktop.getDesktop().requestForeground(true);editor.requestFocusInWindow();
                return null;
            });
            await(()->editor.isFocusOwner()); Thread.sleep(400);
            check(calls.get()==0,"Opening/focusing the source produces no request");
            type("re"); await(()->editor.isAIInlineCompletion());
            check(calls.get()==1,"Typed burst produces one validated suggestion");
            key(KeyEvent.VK_ESCAPE); type("t"); Thread.sleep(650);
            check(calls.get()==1 && edt(()->!editor.isAIInlineCompletion()),"Escape plus nearby typing does not resurrect the suggestion");
            key(KeyEvent.VK_BACK_SPACE); Thread.sleep(300);
            check(calls.get()==1,"Deletion does not solicit inference");
            edt(()->{ai.request(true,false);return null;}); await(()->editor.isAIInlineCompletion());
            key(KeyEvent.VK_TAB); await(()->editor.getText().contains("return value * value;"));
            check(edt(()->editor.getText().contains("return value * value;")),"Explicit request and Tab remain available during suppression");
            edt(()->{editor.undo();return null;});Thread.sleep(350);
            check(edt(()->!editor.getText().contains("return value * value;")),"Accepted completion is one undo operation");
            report.append(edt(ai::metrics)).append('\n');
        } finally {
            Files.writeString(output,report);
            edt(()->{if(ai!=null)ai.close();if(frame!=null)frame.dispose();return null;});
        }
        System.out.print(report);
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);report.append("PASS ").append(message).append('\n');}
    static void type(String value)throws Exception{NativeWindowTestSupport.beforeKey(frame);for(char c:value.toCharArray())edt(()->{Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(editor,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,c));return null;});}
    static void key(int code)throws Exception{NativeWindowTestSupport.beforeKey(frame);edt(()->{Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(editor,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,code,KeyEvent.CHAR_UNDEFINED));return null;});}
    static void await(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){if(edt(()->{NativeWindowTestSupport.recover(frame);return condition.call();}))return;Thread.sleep(10);}throw new AssertionError("Timed out waiting for native state");}
    static <T>T edt(Callable<T> callable)throws Exception{if(SwingUtilities.isEventDispatchThread())return callable.call();var task=new FutureTask<T>(callable);SwingUtilities.invokeAndWait(task);return task.get();}
}

package dev.turboj.app;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextHitInfo;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.text.AttributedString;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Exhaustive ROUTING matrix, not callback-effect verification.
 * The actual frame, dispatcher, profiles, availability predicates and focused widgets are used.
 * Only command handlers are replaced with recording handlers to prevent Quit, shell, downloads,
 * filesystem edits or model changes during the cross-product. Effect tests are separate. */
public final class KeyboardContextMatrixSmoke {
    private static final String SOURCE="class Matrix { int value; int twice(int n) { return n * 2; } }\n";
    private static TurboFrame frame;
    private static Method contextMethod;
    private static Map<String,Command> originals;
    private static final List<String> invoked=new ArrayList<>();
    private static final StringBuilder rows=new StringBuilder("profile\tcontext\tcommand\tshortcut\texpected\tactual\treason\n");
    private static int cases;
    public static void main(String[] args) throws Exception {
        Path output=Files.createDirectories(Path.of("app/target/keyboard-context-matrix"));
        Path work=Files.createTempDirectory("turboj-context-matrix-").toRealPath();
        System.setProperty("turboj.config",work.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        Path project=Files.createDirectories(work.resolve("project"));
        Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=Matrix\n");
        Path source=Files.createDirectories(project.resolve("src/main/java")).resolve("Matrix.java");
        Files.writeString(source,SOURCE);
        contextMethod=TurboFrame.class.getDeclaredMethod("commandContext"); contextMethod.setAccessible(true);
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"fixture project");
            edt(()->{frame.openPath(source);return null;});
            await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"fixture source");
            edt(()->{
                var registry=frame.commandRegistry(); originals=new LinkedHashMap<>();
                registry.commands().forEach(c->originals.put(c.id(),c));
                var aliases=registry.aliases();
                for(String id:originals.keySet())registry.unregister(id);
                for(Command c:originals.values()) registry.register(new Command(c.id(),c.title(),c.description(),c.category(),c.aliases(),
                        c.requiredContexts(),c.cancellable(),c.undoLabel(),Command.Execution.CALLER,c.availability(),(context,token)->{
                            invoked.add(c.id());return CompletableFuture.completedFuture(CommandResult.success());}));
                aliases.forEach(registry::alias);
                return null;
            });
            for(String profile:List.of("intellij-macos","eclipse-macos","vscode-macos","turboj-macos")) {
                fixtureAction("keymap.profile."+profile);
                for(String context:List.of("editor","console","tests","jshell","explorer","dialog","nested-dialog","composition")) {
                    setup(context);
                    for(Command command:originals.values()) {
                        var bindings=edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),command.id()));
                        if(bindings.isEmpty())throw new AssertionError("Unbound "+command.id());
                        for(KeyBinding binding:bindings) {
                            if(context.endsWith("dialog")&&edt(()->OverlayHost.dialogs(frame).size())
                                    !=(context.equals("nested-dialog")?2:1))setup(context);
                            // Local navigation may move focus or close a popup, but the next case starts in its stated context.
                            edt(()->{MenuSelectionManager.defaultManager().clearSelectedPath();return null;});
                            if(!edt(frame::isActive))edt(()->{frame.toFront();Desktop.getDesktop().requestForeground(true);return null;});
                            await(()->frame.isActive()&&focus()!=null,"focus before matrix case");
                            edt(()->{
                                var snapshot=context();
                                var resolver=new ShortcutResolver(frame.commandKeymaps(),frame.commandRegistry());
                                ShortcutResolver.Resolution resolution=null;
                                for(var stroke:binding.sequence().strokes())resolution=resolver.accept(stroke,snapshot,System.nanoTime());
                                boolean blocked=snapshot.has("overlay")||snapshot.has("input-method");
                                String expected=!blocked&&resolution.kind()==ShortcutResolver.Kind.MATCH
                                        &&frame.commandRegistry().availability(resolution.command(),snapshot).enabled()?resolution.command():"";
                                invoked.clear();
                                for(var stroke:binding.sequence().strokes())send(stroke);
                                String actual=String.join(",",invoked);
                                String reason=frame.commandRegistry().availability(command.id(),snapshot).reason();
                                rows.append(profile).append('\t').append(context).append('\t').append(command.id()).append('\t')
                                    .append(binding.sequence().external()).append('\t').append(expected).append('\t').append(actual).append('\t')
                                    .append(reason.replace('\n',' ').replace('\t',' ')).append('\n');
                                cases++;
                                if(!actual.equals(expected))throw new AssertionError(profile+" / "+context+" / "+command.id()+" "+binding.sequence()+": expected="+expected+", actual="+actual);
                                return null;
                            });
                        }
                    }
                    System.out.println("PASS "+profile+" / "+context+" / "+originals.size()+" commands (routing handlers recorded, effects not executed)");
                }
            }
            Files.writeString(output.resolve("report.txt"),"PASS "+cases+" binding/context cases; "+originals.size()+" real descriptors; four profiles; eight native contexts.\nRouting only: command handlers record invocation; callback effects belong to separate native/semantic tests.\n");
        } finally {
            Files.writeString(output.resolve("matrix.tsv"),rows);
            edt(()->{for(var dialog:List.copyOf(OverlayHost.dialogs(frame)))dialog.dispose();frame.shutdown();frame.dispose();return null;});
        }
    }
    private static void setup(String context)throws Exception {
        edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();
            frame.editor().inputMethodTextChanged(new InputMethodEvent(frame.editor(),InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,null,0,null,null));
            frame.intelligence().dismiss();frame.aiCompletion().dismiss();return null;});
        String id=switch(context){case "console"->"view.focusConsole";case "tests"->"view.focusTestResults";case "jshell"->"view.focusJShell";
            case "explorer"->"view.focusExplorer";default->"view.focusEditor";};
        fixtureAction(id);
        await(()->switch(context){case "console"->frame.console().hasConsoleFocus();case "tests"->frame.testResults().view().isFocusOwner();
            case "jshell"->frame.repl().hasConsoleFocus();case "explorer"->frame.mavenProjects().hasExplorerFocus();default->frame.editor().isFocusOwner();},"context "+context);
        if(context.equals("composition"))edt(()->{frame.editor().inputMethodTextChanged(new InputMethodEvent(frame.editor(),InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                new AttributedString("日本").getIterator(),0,TextHitInfo.leading(2),null));return null;});
        if(context.endsWith("dialog")){
            edt(()->{var parent=new EmbeddedDialog(frame,"Matrix parent",false);parent.add(new JTextField(20));parent.pack();parent.setVisible(true);
                if(context.equals("nested-dialog")){var child=new EmbeddedDialog(parent,"Matrix child",false);child.add(new JTextField(20));child.pack();child.setVisible(true);}return null;});
            await(()->focus() instanceof JTextField&&SwingUtilities.isDescendingFrom(focus(),OverlayHost.dialogs(frame).getLast()),"dialog focus");
        }
    }
    private static void fixtureAction(String id)throws Exception { edt(()->{originalsOrRegistry(id).handler().execute(context(),new CancellationToken());return null;}); }
    private static Command originalsOrRegistry(String id){return originals==null?frame.commandRegistry().find(id).orElseThrow():originals.get(id);}
    private static CommandContext context()throws Exception{return (CommandContext)contextMethod.invoke(frame);}
    private static Component focus(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();}
    private static void send(KeyStrokeSpec stroke){var key=SwingKeys.toSwing(stroke);var owner=focus();var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),key.getModifiers(),key.getKeyCode(),KeyEvent.CHAR_UNDEFINED));
        manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),key.getModifiers(),key.getKeyCode(),KeyEvent.CHAR_UNDEFINED));}
    private static void await(Callable<Boolean> condition,String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){if(edt(condition))return;Thread.sleep(10);}throw new AssertionError(name);}
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(30,TimeUnit.SECONDS);}
}

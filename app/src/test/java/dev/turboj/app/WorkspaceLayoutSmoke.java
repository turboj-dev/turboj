package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.AsciiBorder;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.AsciiMenuBar;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/** Desktop verification: single pane borders, one font size, actual divider drag and layout restoration. */
public final class WorkspaceLayoutSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static int checks;
    public static void main(String[] args) throws Exception {
        boolean baseline=Arrays.asList(args).contains("--baseline");
        boolean largeStartup=Arrays.asList(args).contains("--large-font-startup");
        Path output=Path.of(args.length==0 ? "target/native-visual-preview/verification" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Path root=Path.of("/private/tmp/turboj-native-layout",output.getFileName().toString());
        Files.createDirectories(root.resolve("src/main/java"));
        System.setProperty("turboj.config",root.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture.layout</groupId><artifactId>workspace-layout</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Path source=root.resolve("src/main/java/LayoutExample.java");
        Files.writeString(source,"""
                public class LayoutExample {
                    public static void main(String[] args) {
                        System.out.println("One font size.");
                        System.out.println("Resize Output vertically.");
                        System.out.println("Keep editing Java.");
                    }
                }
                """);
        var settings=new Settings();
        if(largeStartup)settings.fontSize=48;
        TurboFrame frame=edt(()-> {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            var value=new TurboFrame(settings,Map.of());value.setSize(largeStartup?640:1080,largeStartup?420:780);value.setVisible(true);value.toFront();
            if(!largeStartup){value.mavenProjects().open(root);value.openPath(source);}return value;
        });
        try {
            if(largeStartup){
                waitFor(()->frame.getInsets().top>0);
                edt(()->{frame.setSize(640,420);frame.validate();
                    var split=(JSplitPane)named(frame,"workspace-output-split");
                    check(split.getTopComponent().getHeight()>=split.getTopComponent().getMinimumSize().height
                            &&split.getBottomComponent().getHeight()>=split.getBottomComponent().getMinimumSize().height,
                            "startup at font48 includes realized native title-bar insets and both pane minima");
                    check(frame.editor().getFont().getSize()==48&&frame.console().inputComponent().getFont().getSize()==48,"startup restores one saved font size");return null;});
                screenshot(frame,output.resolve("startup-48.png"));return;
            }
            waitFor(()->source.equals(frame.currentFile()) && frame.mavenProjects().workspace()!=null && !frame.mavenProjects().busy());
            command(frame,"run");waitFor(()->!frame.isRunning() && frame.console().text().contains("[exit 0") && frame.console().text().contains("One font size"));
            edt(()->{ ((JSplitPane)frame.mavenProjects().panel().getParent()).setDividerLocation(300);
                frame.console().inputComponent().setCaretPosition(0);frame.validate();return null; });
            screenshot(frame,output.resolve(baseline?"before-default-18.png":"after-default-18.png"));
            if(baseline)return;
            JSplitPane split=edt(()->(JSplitPane)named(frame,"workspace-output-split"));
            check(split!=null && split.getOrientation()==JSplitPane.VERTICAL_SPLIT,"output has a real vertical Swing split pane");
            String sourceText=edt(()->frame.editor().getText());String transcript=edt(()->frame.console().text());
            int caret=edt(()->frame.editor().caretOffset());
            for(int size:List.of(16,20,24)) {
                while(settings.fontSize<size)command(frame,"fontUp");
                while(settings.fontSize>size)command(frame,"fontDown");
                edt(()-> {
                    frame.validate();
                    check(AsciiChrome.font().getSize()==size && frame.editor().getFont().getSize()==size,"one editor/chrome point size at "+size);
                    check(frame.console().inputComponent().getFont().getSize()==size,"console text uses "+size+" points");
                    for(Component c:descendants(frame.mavenProjects().panel()))
                        if(c instanceof JTree || c instanceof JLabel || c instanceof AbstractButton)
                            check(c.getFont().getSize()==size,"explorer "+c.getClass().getSimpleName()+" uses "+size+" points");
                    check(((JLabel)named(frame,"workspace-status")).getFont().getSize()==size,"footer uses "+size+" points");
                    for(JMenu menu:((AsciiMenuBar)frame.getJMenuBar()).allMenus())check(menu.getFont().getSize()==size,"menu "+menu.getText()+" uses "+size+" points");
                    for(JComponent panel:List.of((JComponent)frame.editor().getParent(),(JComponent)frame.console().getParent(),frame.mavenProjects().panel())) {
                        check(panel.getBorder() instanceof AsciiBorder,"workspace pane has one ASCII boundary");
                        var border=(AsciiBorder)panel.getBorder();
                        var field=AsciiBorder.class.getDeclaredField("line");field.setAccessible(true);
                        check(field.get(border)==AsciiBorder.Line.SINGLE && border.palette().font().getSize()==size,"pane boundary is single and matches "+size+" point text");
                    }
                    check(split.getDividerSize()>0 && split.getBottomComponent().getHeight()>=split.getBottomComponent().getMinimumSize().height,"output remains visible and resizable");
                    return null;
                });
                screenshot(frame,output.resolve("zoom-"+(size==16?"100":size==20?"125":"150")+"-"+size+"pt.png"));
            }
            while(settings.fontSize>18)command(frame,"fontDown");
            for(String profile:List.of("intellij-macos","eclipse-macos","vscode-macos","turboj-classic")) {
                edt(()->{frame.commandKeymaps().select(profile);return null;});
                command(frame,"view.resizeOutput");waitFor(split::isFocusOwner);
                int location=edt(split::getDividerLocation);
                key(KeyEvent.VK_UP);waitFor(()->split.getDividerLocation()<location);
                key(KeyEvent.VK_DOWN);waitFor(()->split.getDividerLocation()==location);
                key(KeyEvent.VK_ESCAPE);waitFor(()->frame.editor().isFocusOwner());
                check(true,profile+" Resize Output routes arrow keys to vertical divider and Escape to editor");
            }
            int initial=edt(split::getDividerLocation);
            edt(()-> {
                var divider=((BasicSplitPaneUI)split.getUI()).getDivider();
                int x=Math.max(1,divider.getWidth()/2),y=Math.max(1,divider.getHeight()/2);
                long now=System.currentTimeMillis();
                divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,x,y,1,false,MouseEvent.BUTTON1));
                divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_DRAGGED,now+1,InputEvent.BUTTON1_DOWN_MASK,x,y-100,0,false,MouseEvent.NOBUTTON));
                divider.dispatchEvent(new MouseEvent(divider,MouseEvent.MOUSE_RELEASED,now+2,0,x,y-100,1,false,MouseEvent.BUTTON1));
                frame.validate();return null;
            });
            waitFor(()->Math.abs(split.getDividerLocation()-initial)>60);
            int resizedHeight=edt(()->split.getBottomComponent().getHeight());
            check(true,"actual divider mouse events change console height");
            screenshot(frame,output.resolve("output-resized.png"));
            command(frame,"view.toggleConsole");
            edt(()->{frame.validate();check(!split.getBottomComponent().isVisible() && split.getDividerSize()==0,"hiding output removes its divider");return null;});
            command(frame,"view.toggleConsole");
            edt(()->{frame.validate();check(Math.abs(split.getBottomComponent().getHeight()-resizedHeight)<=3,"showing output restores the selected height");return null;});
            command(frame,"view.maximizeEditor");command(frame,"view.restoreLayout");
            edt(()->{frame.validate();check(Math.abs(split.getBottomComponent().getHeight()-resizedHeight)<=3,"maximize/restore retains the selected output height");return null;});
            for(String theme:Theme.BUNDLED)edt(()->{frame.applyTheme(Theme.bundled(theme));frame.validate();check(Math.abs(split.getBottomComponent().getHeight()-resizedHeight)<=3,"theme "+theme+" retains resized output height");return null;});
            command(frame,"repl");edt(()->{frame.validate();check(Math.abs(split.getBottomComponent().getHeight()-resizedHeight)<=3,"JShell shares the chosen output height");return null;});
            edt(()->{split.setDividerLocation(Integer.MAX_VALUE);frame.validate();
                var input=descendants(frame.repl()).stream().filter(c->c instanceof JTextField).findFirst().orElseThrow();
                check(input.getHeight()>=input.getPreferredSize().height,"minimum JShell height retains its complete input field");
                check(split.getBottomComponent().getHeight()>=split.getBottomComponent().getMinimumSize().height,"minimum JShell height retains one output row and input row");
                split.setDividerLocation(0);frame.validate();
                JTree tree=(JTree)descendants(frame.mavenProjects().panel()).stream().filter(c->c instanceof JTree).findFirst().orElseThrow();
                check(((JViewport)tree.getParent()).getHeight()>=tree.getRowHeight(),"maximum output retains one explorer row and its footer");
                split.setDividerLocation(split.getHeight()-split.getDividerSize()-resizedHeight);frame.validate();return null;});
            command(frame,"output");
            edt(()-> {
                check(frame.editor().getText().equals(sourceText) && frame.editor().caretOffset()==caret,"zoom/resize/hide/theme changes retain source and caret");
                check(frame.console().text().equals(transcript),"zoom/resize/hide/theme changes retain actual execution output");
                frame.applyTheme(Theme.bundled("classic"));frame.setSize(640,480);frame.validate();
                check(split.getTopComponent().getHeight()>0 && split.getBottomComponent().getHeight()>0,"compact window retains editor and output");return null;
            });
            screenshot(frame,output.resolve("compact-640.png"));
            command(frame,"view.toggleConsole");
            edt(()->{frame.setSize(760,530);frame.validate();return null;});
            command(frame,"view.toggleConsole");
            edt(()->{frame.validate();check(split.getBottomComponent().getHeight()>0 && split.getTopComponent().getHeight()>0,"hidden output restores within resized window bounds");return null;});
            Path scanner=root.resolve("src/main/java/ScannerExample.java");
            Files.writeString(scanner,"""
                    public class ScannerExample {
                        public static void main(String[] args) {
                            System.out.print("Name? ");
                            String name = new java.util.Scanner(System.in).nextLine();
                            System.out.println("Hello " + name);
                        }
                    }
                    """);
            edt(()->{frame.openPath(scanner);return null;});waitFor(()->scanner.equals(frame.currentFile()));
            command(frame,"run");waitFor(()->frame.isRunning()&&frame.console().text().contains("Name? "));
            edt(()->{split.setDividerLocation(Integer.MAX_VALUE);frame.validate();
                var input=frame.console().inputComponent();
                check(input.isEditable()&&((JViewport)input.getParent()).getHeight()>=input.getFontMetrics(input.getFont()).getHeight(),"minimum program output retains editable Scanner input");
                frame.console().setInputText("Ada");frame.console().submitInput();return null;});
            waitFor(()->!frame.isRunning()&&frame.console().text().contains("Hello Ada")&&frame.console().text().contains("[exit 0"));
            check(true,"actual Scanner input completes after console is resized to its minimum");
            while(settings.fontSize<48)command(frame,"fontUp");
            edt(()->{frame.setSize(640,420);frame.validate();
                check(split.getTopComponent().getHeight()>=split.getTopComponent().getMinimumSize().height && split.getBottomComponent().getHeight()>=split.getBottomComponent().getMinimumSize().height,"font48 minimum window keeps both panes within their bounds");
                var bar=(AsciiMenuBar)frame.getJMenuBar();bar.doLayout();check(bar.overflowMenu().getParent()==bar,"font48 retains native menus through More");return null;});
            screenshot(frame,output.resolve("maximum-font-48.png"));
        } catch(Throwable failure) {
            report.append("FAIL ").append(failure).append('\n');screenshot(frame,output.resolve("failure.png"));throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"),report+"Checks: "+checks+"\n");
            edt(()->{frame.shutdown();frame.dispose();return null;});
        }
    }
    private static Component named(Container root,String name){if(name.equals(root.getName()))return root;for(Component c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container container){var value=named(container,name);if(value!=null)return value;}}return null;}
    private static List<Component> descendants(Container root){List<Component> result=new ArrayList<>();for(Component c:root.getComponents()){result.add(c);if(c instanceof Container container)result.addAll(descendants(container));}return result;}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;report.append("PASS ").append(message).append('\n');System.out.println("PASS "+message);}
    private static void command(TurboFrame frame,String id)throws Exception {
        var result=new CompletableFuture<CommandResult>();
        SwingUtilities.invokeLater(()->frame.commandRegistry().execute(id,CommandContext.of("editor","editable")).stage().whenComplete((value,error)->{if(error==null)result.complete(value);else result.completeExceptionally(error);}));
        if(id.equals("run")) {
            waitFor(()->result.isDone() || OverlayHost.dialogs(frame).stream().anyMatch(d->d.getTitle().equals("Project Trust")));
            edt(()-> { for(var dialog:OverlayHost.dialogs(frame))if(dialog.getTitle().equals("Project Trust"))
                for(Component c:descendants(dialog))if(c instanceof JButton b && b.getText().equals("OK")) { b.doClick(0);break; }
                return null; });
        }
        check(result.get(10,TimeUnit.SECONDS).status()==CommandResult.Status.SUCCEEDED,"command "+id);
    }
    private static void key(int code)throws Exception {
        edt(()->{var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();Component owner=manager.getFocusOwner();if(owner==null)throw new AssertionError("No focus owner");long at=System.currentTimeMillis();manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,at,0,code,KeyEvent.CHAR_UNDEFINED));manager.dispatchEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,at+1,0,code,KeyEvent.CHAR_UNDEFINED));return null;});Thread.sleep(30);
    }
    private static void screenshot(TurboFrame frame,Path path)throws Exception{edt(()->{var image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();frame.paint(g);g.dispose();ImageIO.write(image,"png",path.toFile());return null;});}
    private static void waitFor(Callable<Boolean> predicate)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(System.nanoTime()<end){if(edt(predicate))return;Thread.sleep(20);}throw new AssertionError("Timed out waiting for native layout state");}
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();}
}

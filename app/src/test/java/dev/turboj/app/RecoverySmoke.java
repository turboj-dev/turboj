package dev.turboj.app;

import dev.turboj.config.*;
import dev.turboj.ui.EditorComponent;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static dev.turboj.app.KeyboardActionEffectsSmoke.*;

/** Real native dialog acceptance; the separate process unit control verifies crash/lock behavior. */
public final class RecoverySmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("turboj-recovery-native-");
        System.setProperty("turboj.config", root.resolve("config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path a = root.resolve("A.java"), b = root.resolve("B.java");
        Files.writeString(a,"class A {}\n"); Files.writeString(b,"class B {}\n");
        String first="class A { int unsaved = 1; }\r\n", second="class B { int unsaved = 2; }\n";
        var entries = List.of(entry(a,first,11),entry(b,second,6));
        try(var owner = new SessionRecovery(Settings.directory().resolve("sessions"))) {
            owner.write(new SessionRecovery.Snapshot(2,a.toString(),entries));
        }
        frame = edt(() -> { var value=new TurboFrame(new Settings(),Map.of()); value.setVisible(true); return value; });
        try {
            edt(() -> { frame.offerRecovery(); return null; }); dialog(); button("Yes");
            await(() -> a.equals(frame.currentFile()),"recovered active file");
            edt(() -> { check(frame.editor().getText().equals(first),"A text"); check(frame.editor().caretOffset()==11,"A caret"); return null; });
            check(Files.readString(a).equals("class A {}\n"),"recovery never overwrites disk");
            edt(() -> { frame.openPath(b); return null; });
            await(() -> b.equals(frame.currentFile()),"B navigation");
            edt(() -> { check(frame.editor().getText().equals(second),"B text"); check(frame.editor().caretOffset()==6,"B caret"); return null; });
            var capture=TurboFrame.class.getDeclaredMethod("captureRecovery"); capture.setAccessible(true);
            @SuppressWarnings("unchecked") Supplier<SessionRecovery.Snapshot> supplier=edt(() -> (Supplier<SessionRecovery.Snapshot>)capture.invoke(frame));
            var snapshot=supplier.get();
            check(snapshot.entries().stream().filter(e -> e.file()!=null).count()==2,"both file buffers captured");
            check(snapshot.entries().stream().filter(e -> e.file()!=null).allMatch(SessionRecovery.Entry::dirty),"dirty state retained");
            check(snapshot.activeKey().equals(b.toString()),"active buffer captured");
            var recoveryField=TurboFrame.class.getDeclaredField("recovery"); recoveryField.setAccessible(true);
            var manifestField=SessionRecovery.class.getDeclaredField("manifest"); manifestField.setAccessible(true);
            Path manifest=(Path)manifestField.get(recoveryField.get(frame));
            edt(() -> { frame.editor().setSelection(frame.editor().getText().length(),frame.editor().getText().length()); frame.editor().requestFocusInWindow(); return null; });
            await(() -> frame.editor().isFocusOwner(),"typing focus");
            boolean checkpointWhileTyping=false;
            for(int index=0;index<70;index++) {
                typeSpace(); Thread.sleep(80);
                if(Files.isRegularFile(manifest) && retained(manifest,b).length()>second.length()) checkpointWhileTyping=true;
            }
            check(checkpointWhileTyping,"continuous typing does not postpone recovery indefinitely");
            String beforeAttachment=edt(() -> frame.editor().getText());
            await(() -> Files.isRegularFile(manifest) && retained(manifest,b).equals(beforeAttachment),"latest typing snapshot durable");
            typeSpace(); String latest=edt(() -> frame.editor().getText());
            Path attachment=root.resolve("Library.java"); Files.writeString(attachment,"class Library {}\n");
            var externalField=TurboFrame.class.getDeclaredField("externalSourceFiles"); externalField.setAccessible(true);
            edt(() -> { @SuppressWarnings("unchecked") var external=(Set<Path>)externalField.get(frame); external.add(attachment); frame.openPath(attachment); return null; });
            await(() -> attachment.equals(frame.currentFile()) && frame.editor().isReadOnly(),"read-only source attachment");
            await(() -> Files.isRegularFile(manifest) && retained(manifest,b).equals(latest),"read-only navigation preserves other pending dirty snapshots");
            check(!Files.readString(manifest).contains("Library.java"),"read-only attachment is excluded without dropping editable buffers");
            System.out.println("PASS recovery: two paths/texts/carets, dirty state, current active buffer, disk unchanged, keyboard confirmation");
            System.out.println("PASS continuous typing checkpoints and pending dirty buffers while viewing read-only attachment");
        } finally { edt(() -> { frame.shutdown(); return null; }); }
        System.exit(0);
    }
    private static void typeSpace() throws Exception {
        for(int attempt=0;attempt<20;attempt++) {
            NativeWindowTestSupport.beforeKey(frame);
            await(() -> frame.editor().isFocusOwner(),"genuine editor focus before typing");
            boolean posted=edt(() -> {
                var owner=focus(); if(owner!=frame.editor())return false;
                var queue=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
                for(int kind:new int[]{java.awt.event.KeyEvent.KEY_PRESSED,java.awt.event.KeyEvent.KEY_TYPED,java.awt.event.KeyEvent.KEY_RELEASED})
                    queue.postEvent(new java.awt.event.KeyEvent(owner,kind,System.currentTimeMillis(),0,
                            kind==java.awt.event.KeyEvent.KEY_TYPED?java.awt.event.KeyEvent.VK_UNDEFINED:java.awt.event.KeyEvent.VK_SPACE,' '));
                return true;
            });
            if(posted) { edt(() -> null); return; }
        }
        throw new AssertionError("Editor keyboard focus did not remain stable; no event fabricated");
    }
    private static String retained(Path manifest,Path source) throws Exception {
        var value=com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        for(var item:value.getAsJsonArray("entries")) {
            var entry=item.getAsJsonObject();
            if(entry.has("file") && !entry.get("file").isJsonNull() && entry.get("file").getAsString().equals(source.toString())) return entry.get("text").getAsString();
        }
        return "";
    }
    private static SessionRecovery.Entry entry(Path file,String text,int caret) throws Exception {
        return new SessionRecovery.Entry(file.toString(),file.toString(),text,HexFormat.of().formatHex(SafeFiles.utf8Fingerprint(text)),
                HexFormat.of().formatHex(SafeFiles.fingerprint(file)),true,caret,caret,0,0,"UTF-8","");
    }
}

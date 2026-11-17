package dev.turboj.app;

import java.awt.*;
import java.util.WeakHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Desktop-fixture activation only. Never chooses a dialog, editor or panel focus target. */
final class NativeWindowTestSupport {
    private static final WeakHashMap<Window,Long> attempts = new WeakHashMap<>();
    private NativeWindowTestSupport() {}

    static boolean owned(Window window, Window owner) {
        for (Window current=window; current!=null; current=current.getOwner()) if(current==owner)return true;
        return false;
    }

    /** Invoke on the EDT. macOS may activate the host terminal when a worker JVM exits. */
    static void recover(Window owner) {
        if(owner==null||!owner.isShowing()||owned(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),owner))return;
        long now=System.nanoTime();
        if(now-attempts.getOrDefault(owner,0L)<TimeUnit.SECONDS.toNanos(1))return;
        attempts.put(owner,now);
        System.out.println("Driver restores owned application's foreground; component focus is not assigned");
        owner.toFront();
        if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))Desktop.getDesktop().requestForeground(true);
    }

    static void beforeKey(Window owner)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){
            var check=new FutureTask<Boolean>(() -> {
                recover(owner);
                var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
                Component focus=manager.getFocusOwner();
                return focus!=null&&focus!=owner&&owned(manager.getActiveWindow(),owner)
                        &&owned(SwingUtilities.getWindowAncestor(focus),owner);
            });
            SwingUtilities.invokeLater(check);
            if(check.get(15,TimeUnit.SECONDS))return;
            Thread.sleep(20);
        }
        throw new AssertionError("Owned native window has no stable keyboard target; no event posted");
    }
}

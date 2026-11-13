package org.openpnp.machine.orion.sheets;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.swing.JOptionPane;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.ConveyorStatus;
import org.openpnp.machine.orion.protocol.OrionBusListener;
import org.openpnp.machine.orion.protocol.OrionConveyor;

/**
 * Runs conveyor actions off the Swing thread, one at a time (the belt moves for seconds), and reports a failure in a
 * dialog. Conveyor work is not a machine task: it must keep working while a job runs.
 */
public final class OrionConveyorUi {
    private OrionConveyorUi() {}

    public interface Action {
        void run(OrionConveyor c) throws Exception;
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orion-Conveyor");
        t.setDaemon(true);
        return t;
    });
    private static volatile int running;

    /** True while an action is running (the status poll then leaves the bus to it). */
    public static boolean isBusy() {
        return running > 0;
    }

    /**
     * @param onStatus gets every status the action polled while the belt moves (called on the worker thread)
     */
    public static void run(String what, Action action, Consumer<ConveyorStatus> onStatus, Runnable afterwards) {
        running++;
        EXEC.submit(() -> {
            try {
                OrionConveyor c = OrionManager.get().getConveyor();
                if (c == null) {
                    throw new Exception("No conveyor answers. Scan rail 2 first.");
                }
                action.run(c);
            } catch (Throwable t) {
                OrionManager.get().addLog(0, OrionBusListener.Direction.ERROR, "Conveyor " + what + " failed: "
                        + t.getMessage());
                OrionUi.onEdt(() -> JOptionPane.showMessageDialog(MainFrame.get(), t.getMessage(),
                        "Conveyor: " + what, JOptionPane.WARNING_MESSAGE));
            } finally {
                running--;
                if (afterwards != null) {
                    OrionUi.onEdt(afterwards);
                }
            }
        });
    }

    /** Load the board at the entry (or the next one ready), then clamp it. */
    public static void loadBoard(Consumer<ConveyorStatus> onStatus, Runnable afterwards) {
        run("load", c -> {
            ConveyorStatus s = c.loadBoard(true, onStatus);
            OrionManager.get().setConveyorStatus(s);
            OrionManager.get().addLog(0, OrionBusListener.Direction.INFO, String.format(
                    "Conveyor: board loaded, %.1f mm long, clamped", s.lengthMm()));
        }, onStatus, afterwards);
    }
}

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

    /** The board the job expects: its two sides, where they came from, and its placements (for the edge check). */
    public static class Expected {
        public double a;
        public double b;
        public String source = "";
        public double dimX;
        public double dimY;
        public java.util.List<double[]> placements = new java.util.ArrayList<>();
    }

    /** Manual size from the settings, else the job's panel / board; null when neither is known. */
    public static Expected expected() {
        org.openpnp.machine.orion.OrionSettings st = OrionManager.get().getSettings();
        Expected e = new Expected();
        try {
            org.openpnp.model.Job job = MainFrame.get().getJobTab().getJob();
            if (job != null && job.getRootPanelLocation() != null) {
                org.openpnp.model.PanelLocation root = job.getRootPanelLocation();
                org.openpnp.model.Location d = root.getPlacementsHolder().getDimensions();
                e.dimX = d.getLengthX().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue();
                e.dimY = d.getLengthY().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue();
                java.util.List<org.openpnp.model.PlacementsHolderLocation<?>> kids = root.getChildren();
                if (kids.size() == 1) {
                    // one board: its placements can be checked against the edge strip
                    org.openpnp.model.PlacementsHolder<?> h = kids.get(0).getPlacementsHolder();
                    org.openpnp.model.Location bd = h.getDimensions();
                    e.dimX = bd.getLengthX().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue();
                    e.dimY = bd.getLengthY().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getValue();
                    for (org.openpnp.model.Placement p : h.getPlacements()) {
                        org.openpnp.model.Location l = p.getLocation().convertToUnits(
                                org.openpnp.model.LengthUnit.Millimeters);
                        e.placements.add(new double[] {l.getX(), l.getY()});
                    }
                }
            }
        } catch (Throwable t) {
            // no job open
        }
        if (st.getExpectedBoardA() > 0 && st.getExpectedBoardB() > 0) {
            e.a = st.getExpectedBoardA();
            e.b = st.getExpectedBoardB();
            e.source = "set by hand";
            return e;
        }
        if (e.dimX > 0 && e.dimY > 0) {
            e.a = e.dimX;
            e.b = e.dimY;
            e.source = "from the job";
            return e;
        }
        return null;
    }

    /**
     * A warning when the measured length or the set width does not fit the expected board, or placements lie in the
     * edge strip; null when everything fits or nothing can be compared.
     */
    public static String sizeWarning(ConveyorStatus s, Double widthTenthsOverride) {
        org.openpnp.machine.orion.OrionSettings st = OrionManager.get().getSettings();
        if (!st.isBoardSizeCheck() || s == null) {
            return null;
        }
        Expected e = expected();
        if (e == null) {
            return null;
        }
        double len = s.length >= 0 && s.boardLoaded() ? s.lengthMm() : Double.NaN;
        double wid = widthTenthsOverride != null ? widthTenthsOverride / 10.0 : s.widthMm();
        org.openpnp.machine.orion.OrionBoardCheck.Result r = org.openpnp.machine.orion.OrionBoardCheck.checkSize(len,
                wid, e.a, e.b, st.getBoardSizeTolMm(), st.getWidthClearanceMm());
        StringBuilder sb = new StringBuilder();
        if (!r.ok) {
            sb.append(r.message);
        }
        if (!e.placements.isEmpty() && st.getBoardEdgeMm() > 0) {
            int n = org.openpnp.machine.orion.OrionBoardCheck.placementsInEdge(e.placements, e.dimX, e.dimY,
                    st.getBoardEdgeMm());
            if (n > 0) {
                sb.append(sb.length() > 0 ? " " : "").append(n).append(" placement(s) lie within ")
                        .append(String.format("%.1f", st.getBoardEdgeMm()))
                        .append(" mm of the board edge, where the guides and the clamp are.");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Load the board at the entry (or the next one ready), then clamp it. */
    public static void loadBoard(Consumer<ConveyorStatus> onStatus, Runnable afterwards) {
        run("load", c -> {
            ConveyorStatus s = c.loadBoard(true, onStatus);
            OrionManager.get().setConveyorStatus(s);
            OrionManager.get().addLog(0, OrionBusListener.Direction.INFO, String.format(
                    "Conveyor: board loaded, %.1f mm long, clamped", s.lengthMm()));
            String w = sizeWarning(s, null);
            if (w != null) {
                OrionManager.get().notice(new OrionManager.Notice(OrionManager.Notice.Kind.ERROR, 0,
                        "Check the board: " + w));
            }
        }, onStatus, afterwards);
    }
}

package org.openpnp.machine.orion.sheets;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.OrionSettings;
import org.openpnp.machine.orion.vision.OrionRailScanner;

/** Starts the setup cycle from the menu, the bus manager or the "machine homed" message. */
public final class OrionSetupUi {
    private OrionSetupUi() {}

    /** Run the cycle on one rail (or every configured rail when rail is null), then show what happened. */
    public static void start(Integer rail, OrionRailScanner.Progress progress) {
        final OrionManager mgr = OrionManager.get();
        OrionUi.run("Setup cycle", () -> {
            mgr.connectIfNeeded();
            OrionSettings st = mgr.getSettings();
            StringBuilder out = new StringBuilder();
            int rails = Math.max(2, mgr.getRailCount());
            boolean any = false;
            for (int r = 0; r < rails; r++) {
                if ((rail != null && rail != r) || mgr.getBus(r) == null) {
                    continue;
                }
                if (!st.getRailSettings(r).isConfigured()) {
                    if (rail != null) {
                        throw new Exception("Rail " + (r + 1) + " has no X range set. Fill in the vision scan "
                                + "settings of the rail first.");
                    }
                    continue;
                }
                any = true;
                OrionManager.SetupReport rep = mgr.runSetupCycle(r, progress);
                out.append("Rail ").append(r + 1).append(": ").append(rep.fine).append(" feeder(s) set up, ")
                        .append(rep.problems).append(" need attention\n");
                for (String l : rep.lines) {
                    out.append("   ").append(l).append('\n');
                }
                out.append('\n');
            }
            if (!any) {
                throw new Exception("No rail is set up for vision yet (Bus Manager > Rail vision scan).");
            }
            return out.toString();
        }, text -> {
            JTextArea area = new JTextArea(text, 18, 90);
            area.setEditable(false);
            JOptionPane.showMessageDialog(MainFrame.get(), new JScrollPane(area), "Setup cycle finished",
                    JOptionPane.INFORMATION_MESSAGE);
        });
    }
}

package org.openpnp.machine.orion.sheets;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBusListener;
import org.openpnp.util.UiUtils;

/** Small shared helpers for the Orion tabs. */
final class OrionUi {
    private OrionUi() {}

    /**
     * Run a hardware action as a machine task (serialised with other machine work, allowed even while
     * the machine is not enabled since the feeder bus is independent of the motion controller),
     * report errors in a dialog and in the Orion log, then call onDone on the UI thread.
     */
    static <T> void run(String what, Callable<T> task, Consumer<T> onDone) {
        UiUtils.submitUiMachineTask(task, result -> {
            if (onDone != null) {
                onDone.accept(result);
            }
        }, t -> {
            OrionManager.get().addLog(0, OrionBusListener.Direction.ERROR, what + " failed: " + t.getMessage());
            MessageBoxes.errorBox(MainFrame.get(), "Orion: " + what, t);
        }, true);
    }

    static void run(String what, UiUtils.Thrunnable task) {
        run(what, () -> {
            task.thrun();
            return null;
        }, null);
    }

    static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Add a "label: component" row to a GridBag panel. */
    static void row(JPanel p, int row, String label, Component c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.LINE_END;
        l.insets = new Insets(2, 4, 2, 6);
        p.add(new JLabel(label), l);
        GridBagConstraints r = new GridBagConstraints();
        r.gridx = 1;
        r.gridy = row;
        r.weightx = 1;
        r.fill = GridBagConstraints.HORIZONTAL;
        r.anchor = GridBagConstraints.LINE_START;
        r.insets = new Insets(2, 0, 2, 4);
        p.add(c, r);
    }

    static JPanel grid() {
        return new JPanel(new GridBagLayout());
    }
}

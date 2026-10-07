package org.openpnp.machine.orion.sheets;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.openpnp.machine.orion.OrionManager;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionBusListener;
import org.openpnp.machine.orion.protocol.OrionCommand;
import org.openpnp.machine.orion.protocol.OrionFrame;

/** Detailed debugging: live frame log with filters, raw command sender, status / I2C probes. */
public class OrionDebugPanel extends JPanel implements OrionManager.Listener {
    private final OrionManager mgr = OrionManager.get();
    private final JTextArea logArea = new JTextArea();
    private final JCheckBox showTx = new JCheckBox("TX", true);
    private final JCheckBox showRx = new JCheckBox("RX", true);
    private final JCheckBox showInfo = new JCheckBox("Info", true);
    private final JCheckBox showErr = new JCheckBox("Errors", true);
    private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);
    private final JComboBox<String> railFilter = new JComboBox<>(new String[] {"All rails", "Rail 1",
            "Rail 2"});

    private final JComboBox<String> railSel = new JComboBox<>(new String[] {"Rail 1", "Rail 2"});
    private final JTextField addr = new JTextField("1", 4);
    private final JTextField cmd = new JTextField("01", 4);
    private final JTextField payload = new JTextField(16);
    private final JTextField timeout = new JTextField("200", 5);
    private final JLabel result = new JLabel(" ");

    public OrionDebugPanel() {
        setLayout(new BorderLayout());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Show:"));
        for (JCheckBox c : new JCheckBox[] {showTx, showRx, showInfo, showErr}) {
            c.addActionListener(e -> rebuild());
            top.add(c);
        }
        railFilter.addActionListener(e -> rebuild());
        top.add(railFilter);
        top.add(autoScroll);
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> mgr.clearLog());
        top.add(clear);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(logArea.getText()), null));
        top.add(copy);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel raw = OrionUi.grid();
        raw.setBorder(BorderFactory.createTitledBorder("Raw command"));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT));
        line.add(railSel);
        line.add(new JLabel("Addr (0=bcast)"));
        line.add(addr);
        line.add(new JLabel("Cmd hex"));
        line.add(cmd);
        line.add(new JLabel("Payload hex"));
        line.add(payload);
        line.add(new JLabel("Timeout ms"));
        line.add(timeout);
        JButton send = new JButton("Send");
        send.addActionListener(e -> sendRaw());
        line.add(send);
        OrionUi.row(raw, 0, "", line);

        JPanel probes = new JPanel(new FlowLayout(FlowLayout.LEFT));
        probes.add(probe("GET_STATUS", (b, a) -> b.getStatus(a).toString()));
        probes.add(probe("I2C scan", (b, a) -> "I2C devices: " + OrionFrame.toHex(b.i2cScan(a))));
        probes.add(probe("GET_COMPONENT", (b, a) -> {
            int[] c = b.getComponent(a);
            return String.format("component=%d zero=%d halfTeeth=%d", c[0], c[1], c[2]);
        }));
        probes.add(probe("Peel settings", (b, a) -> String.format("peelTime=%d ms, peelRate=%d (0.1 ms/mm)",
                b.getPeelTimeMs(a), b.getPeelRate(a))));
        probes.add(probe("Re-verify serial", (b, a) -> {
            String s = b.getDevice(a) == null ? null : b.getDevice(a).serial;
            return s == null ? "unknown address"
                    : (b.verifySerial(a, s) ? "serial matches " + s : "SERIAL MISMATCH");
        }));
        OrionUi.row(raw, 1, "Probes (use Addr above)", probes);
        OrionUi.row(raw, 2, "Result", result);
        add(raw, BorderLayout.SOUTH);

        mgr.addListener(this);
        rebuild();
    }

    private interface Probe {
        String run(OrionBus bus, int address) throws Exception;
    }

    private JButton probe(String name, Probe p) {
        JButton b = new JButton(name);
        b.addActionListener(e -> {
            OrionBus bus = mgr.getBus(railSel.getSelectedIndex());
            int a;
            try {
                a = Integer.parseInt(addr.getText().trim());
            } catch (NumberFormatException ex) {
                result.setText("Bad address");
                return;
            }
            if (bus == null) {
                result.setText("Not connected");
                return;
            }
            OrionUi.run(name, () -> p.run(bus, a), text -> result.setText(text));
        });
        return b;
    }

    private void sendRaw() {
        OrionBus bus = mgr.getBus(railSel.getSelectedIndex());
        if (bus == null) {
            result.setText("Not connected");
            return;
        }
        try {
            int a = Integer.parseInt(addr.getText().trim());
            int c = Integer.parseInt(cmd.getText().trim(), 16);
            byte[] pl = OrionFrame.fromHex(payload.getText());
            int to = Integer.parseInt(timeout.getText().trim());
            boolean bcast = a == OrionCommand.ADDR_BROADCAST;
            OrionUi.run("Raw send", () -> {
                List<OrionFrame> r = bus.exchange(new OrionFrame(a, c, pl), to, bcast ? to : 0);
                StringBuilder sb = new StringBuilder();
                for (OrionFrame f : r) {
                    sb.append(OrionCommand.name(f.command)).append(' ').append(f).append("; ");
                }
                return r.isEmpty() ? "(no reply)" : sb.toString();
            }, text -> result.setText(text));
        } catch (Exception e) {
            result.setText("Bad input: " + e.getMessage());
        }
    }

    private boolean wanted(OrionManager.LogEntry e) {
        int rf = railFilter.getSelectedIndex() - 1;
        if (rf >= 0 && e.rail != rf) {
            return false;
        }
        switch (e.direction) {
            case TX:
                return showTx.isSelected();
            case RX:
                return showRx.isSelected();
            case INFO:
                return showInfo.isSelected();
            default:
                return showErr.isSelected();
        }
    }

    private void rebuild() {
        StringBuilder sb = new StringBuilder();
        for (OrionManager.LogEntry e : mgr.getLog()) {
            if (wanted(e)) {
                sb.append(e).append('\n');
            }
        }
        logArea.setText(sb.toString());
        if (autoScroll.isSelected()) {
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    @Override
    public void orionChanged() {
        OrionUi.onEdt(this::rebuild);
    }

    @Override
    public void removeNotify() {
        mgr.removeListener(this);
        super.removeNotify();
    }
}

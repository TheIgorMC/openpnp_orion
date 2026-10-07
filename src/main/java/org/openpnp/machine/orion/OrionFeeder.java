package org.openpnp.machine.orion;

import java.util.ArrayList;
import java.util.List;

import javax.swing.Action;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.orion.protocol.OrionBus;
import org.openpnp.machine.orion.protocol.OrionDeviceInfo;
import org.openpnp.machine.orion.protocol.OrionError;
import org.openpnp.machine.orion.protocol.OrionException;
import org.openpnp.machine.orion.sheets.OrionBusManagerSheet;
import org.openpnp.machine.orion.sheets.OrionDebugSheet;
import org.openpnp.machine.orion.sheets.OrionFeederSheet;
import org.openpnp.machine.reference.ReferenceFeeder;
import org.openpnp.model.Location;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * A feeder on the OrionPnP RS485 bus. The feeder is bound to physical hardware by its factory
 * serial number, not by its (disposable) bus address, so it follows the unit across reboots,
 * rails and slots. Pitch / peel settings live here as well as on the unit, and are pushed to the
 * unit whenever it shows up with different values.
 */
public class OrionFeeder extends ReferenceFeeder {
    /** 32 hex chars of the factory serial, or null when not bound to hardware yet. */
    @Attribute(required = false)
    protected String serial;

    /** Last rail this feeder was seen on (hint only; the serial is the identity). */
    @Attribute(required = false)
    protected int rail = 0;

    @Attribute(required = false)
    protected int pitchMm = 4;

    /** Peel coupling in ms of peel per mm of sprocket travel. 0 = off, negative = leave as is. */
    @Attribute(required = false)
    protected double peelMsPerMm = -1;

    /** Standalone peel duration used by the Peel / Unpeel buttons, ms. Negative = leave as is. */
    @Attribute(required = false)
    protected int peelTimeMs = -1;

    @Attribute(required = false)
    protected int ledBrightness = 0;

    @Attribute(required = false)
    protected int feedRetries = 2;

    /** Set once this session's settings have been pushed to the unit. */
    private transient String configuredForSerialAddress;

    public OrionFeeder() {
    }

    // ------------------------------------------------------------------ properties

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        Object old = this.serial;
        this.serial = serial == null || serial.isEmpty() ? null : serial.toUpperCase();
        configuredForSerialAddress = null;
        firePropertyChange("serial", old, this.serial);
    }

    public int getRail() {
        return rail;
    }

    public void setRail(int rail) {
        this.rail = rail;
    }

    public int getPitchMm() {
        return pitchMm;
    }

    public void setPitchMm(int pitchMm) {
        this.pitchMm = pitchMm;
        configuredForSerialAddress = null;
    }

    public double getPeelMsPerMm() {
        return peelMsPerMm;
    }

    public void setPeelMsPerMm(double v) {
        this.peelMsPerMm = v;
        configuredForSerialAddress = null;
    }

    public int getPeelTimeMs() {
        return peelTimeMs;
    }

    public void setPeelTimeMs(int v) {
        this.peelTimeMs = v;
        configuredForSerialAddress = null;
    }

    public int getLedBrightness() {
        return ledBrightness;
    }

    public void setLedBrightness(int v) {
        this.ledBrightness = v;
        configuredForSerialAddress = null;
    }

    public int getFeedRetries() {
        return feedRetries;
    }

    public void setFeedRetries(int feedRetries) {
        this.feedRetries = feedRetries;
    }

    /** X position of this feeder along the rail, mm (machine coordinates). */
    public double getSlotX() {
        return location.getX();
    }

    // ------------------------------------------------------------------ hardware access

    /** A live handle: bus + current address. */
    public static class Link {
        public final OrionBus bus;
        public final OrionDeviceInfo info;

        Link(OrionBus bus, OrionDeviceInfo info) {
            this.bus = bus;
            this.info = info;
        }

        public int address() {
            return info.address;
        }
    }

    /** Find the unit (scanning once if it is not known), verify it, and push settings if needed. */
    public synchronized Link link() throws Exception {
        if (serial == null) {
            throw new OrionException(OrionException.Kind.NOT_FOUND, "Feeder '" + getName()
                    + "' is not bound to hardware. Open the Orion Bus Manager tab, scan, and bind a unit.");
        }
        OrionManager mgr = OrionManager.get();
        OrionManager.Located l = mgr.locateOrScan(serial, rail);
        if (l == null) {
            throw new OrionException(OrionException.Kind.NOT_FOUND, "Feeder '" + getName()
                    + "' (serial " + serial + ") was not found on any rail. Is it plugged in and the rail powered?");
        }
        if (l.info.state == OrionDeviceInfo.State.CONFLICT) {
            throw new OrionException(OrionException.Kind.CONFLICT, "Feeder '" + getName()
                    + "' is in conflict: " + l.info.note);
        }
        if (rail != l.bus.getRail()) {
            rail = l.bus.getRail();
        }
        Link link = new Link(l.bus, l.info);
        String key = serial + "@" + l.info.address;
        if (!key.equals(configuredForSerialAddress)) {
            applySettings(link);
            configuredForSerialAddress = key;
        }
        return link;
    }

    /** Push pitch / peel / LED settings that are set (non-negative) to the unit. */
    public void applySettings(Link link) throws OrionException {
        OrionBus bus = link.bus;
        int a = link.address();
        if (pitchMm > 0) {
            bus.setPitchMm(a, pitchMm);
        }
        if (peelMsPerMm >= 0) {
            bus.setPeelRate(a, (int) Math.round(peelMsPerMm * 10.0));
        }
        if (peelTimeMs >= 0) {
            bus.setPeelTimeMs(a, peelTimeMs);
        }
        if (ledBrightness > 0) {
            bus.setLedBrightness(a, ledBrightness);
        }
    }

    /** Read pitch/peel settings back from the unit into this feeder (does not change location). */
    public void readSettingsFromUnit() throws Exception {
        Link link = link();
        int a = link.address();
        int rate = link.bus.getPeelRate(a);
        int time = link.bus.getPeelTimeMs(a);
        int[] comp = link.bus.getComponent(a);
        setPeelMsPerMm(rate < 0 ? -1 : rate / 10.0);
        setPeelTimeMs(time);
        if (comp[2] > 0) {
            setPitchMm(comp[2] * 2);
        }
        configuredForSerialAddress = serial + "@" + a;
    }

    public void identify() throws Exception {
        Link l = link();
        l.bus.identifyBlink(l.address(), 5);
    }

    public void feedOnce() throws Exception {
        runMove(Move.FEED);
    }

    public void unfeedOnce() throws Exception {
        runMove(Move.UNFEED);
    }

    public void peel(boolean reverse) throws Exception {
        Link l = link();
        l.bus.peelCalibrated(l.address(), reverse);
    }

    public void stop() throws Exception {
        Link l = link();
        l.bus.stop(l.address());
    }

    public int jogTenthsMm(int tenths) throws Exception {
        Link l = link();
        return l.bus.jog(l.address(), tenths);
    }

    public void zeroHere() throws Exception {
        Link l = link();
        l.bus.zeroHere(l.address());
    }

    private enum Move { FEED, UNFEED }

    private void runMove(Move move) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= feedRetries; attempt++) {
            Link l;
            try {
                l = link();
            } catch (OrionException e) {
                throw e;
            }
            try {
                if (move == Move.FEED) {
                    l.bus.feedNext(l.address());
                } else {
                    l.bus.feedBack(l.address());
                }
                return;
            } catch (OrionException e) {
                last = e;
                if (e.kind == OrionException.Kind.TIMEOUT) {
                    // Unit may have rebooted and lost its address: forget and re-find it.
                    Logger.warn("Orion feeder " + getName() + ": no reply, rescanning");
                    configuredForSerialAddress = null;
                    l.bus.scan(null);
                    continue;
                }
                if (e.kind == OrionException.Kind.NACK && e.error == OrionError.NOT_READY) {
                    configuredForSerialAddress = null;
                    continue; // link() pushes the pitch and we retry
                }
                throw new OrionException(e.kind, e.error, "Feeder '" + getName() + "' failed to "
                        + (move == Move.FEED ? "feed" : "unfeed") + ": " + e.getMessage());
            }
        }
        throw new OrionException(OrionException.Kind.TIMEOUT, OrionError.NONE,
                "Feeder '" + getName() + "' did not respond to " + move + " after "
                        + (feedRetries + 1) + " attempts: " + (last == null ? "" : last.getMessage()));
    }

    // ------------------------------------------------------------------ Feeder interface

    @Override
    public void feed(Nozzle nozzle) throws Exception {
        switch (getFeedOptions()) {
            case Normal:
                break;
            case SkipNext:
                setFeedOptions(FeedOptions.Normal);
                return;
            case Disable:
                return;
        }
        runMove(Move.FEED);
    }

    @Override
    public Location getPickLocation() throws Exception {
        return location;
    }

    @Override
    public boolean supportsFeedOptions() {
        return true;
    }

    @Override
    public boolean canTakeBackPart() {
        return getFeedOptions() == FeedOptions.Normal;
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        super.takeBackPart(nozzle);
        putPartBack(nozzle);
        setFeedOptions(FeedOptions.SkipNext);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && serial != null;
    }

    // ------------------------------------------------------------------ UI

    @Override
    public String getPropertySheetHolderTitle() {
        return String.format("%s %s", getClass().getSimpleName(), getName());
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        List<PropertySheet> sheets = new ArrayList<>();
        sheets.add(new OrionFeederSheet(this));
        sheets.add(new OrionBusManagerSheet());
        sheets.add(new OrionDebugSheet());
        return sheets.toArray(new PropertySheet[0]);
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return new PropertySheetHolder[0];
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[0];
    }

    @Override
    public Wizard getConfigurationWizard() {
        return null;
    }

    @Override
    public String toString() {
        return getName();
    }
}

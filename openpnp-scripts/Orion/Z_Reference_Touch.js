/**
 * Orion Z reference touch (by hand): one fixed surface, the machine's PRIMARY calibration fiducial, is the Z truth.
 * Each nozzle / tip is stepped down onto it until it just touches, and the touch Z is logged. One nozzle + tip is the
 * REFERENCE (primary truth); every other nozzle / tip is reported as its difference to it. That difference is what a
 * tip is "longer" by (positive = it touches at a higher Z), i.e. the Z trim to give it.
 *
 * Install: copy into %USERPROFILE%\.openpnp2\scripts\Orion\  (Scripts > Refresh Scripts), run from Scripts > Orion.
 *
 * How to use: load the nozzle tip you want to measure, run the script, pick the nozzle. It goes to the safe Z, over the
 * primary calibration fiducial (Machine Setup > Heads > Calibration: primary fiducial), and down to START_ABOVE_MM above
 * the fiducial's Z. Then step down (0.5 / 0.1 / 0.02) until the tip JUST touches (a strip of paper that starts to drag is
 * a good feeler), and press TOUCHING. It never goes more than MAX_DOWN_MM below its start.
 *
 * The first time (or when you answer Yes to "make this the reference") that nozzle + tip becomes the primary truth.
 * Log: %USERPROFILE%\.openpnp2\z_reference_log.csv  (date, nozzle, tip, Z mm, reference flag). The table shown after
 * each touch lists the latest touch of every nozzle / tip and its difference to the reference.
 *
 * Where to put the numbers: Bus manager > Nozzle pick trim > the tip's "pick Z trim" (Orion feeder picks). OpenPnP has
 * no per-tip Z offset for plain nozzles, so placing is not covered: for that, a nozzle's head offset Z is the place,
 * but changing it also moves OpenPnP's dependent offsets, so do it deliberately and rarely.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var START_ABOVE_MM = 1.0;    // start this far above the fiducial's Z
var MAX_DOWN_MM = 2.0;       // never lower than this below the start (so at most 1 mm below the fiducial's Z)
var SPEED = 0.25;            // fraction of full speed for the Z moves
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

task(function () {
    var File = Java.type('java.io.File');
    var Files = Java.type('java.nio.file.Files');
    var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
    var StandardOpenOption = Java.type('java.nio.file.StandardOpenOption');
    var Location = Java.type('org.openpnp.model.Location');
    var LengthUnit = Java.type('org.openpnp.model.LengthUnit');
    var JOptionPane = Java.type('javax.swing.JOptionPane');

    function ask(msg, options) {
        return JOptionPane.showOptionDialog(null, msg, 'Z reference touch', JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, Java.to(options, 'java.lang.Object[]'), options[0]);
    }

    function mm(loc) {
        return loc.convertToUnits(LengthUnit.Millimeters);
    }

    function zOf(nozzle) {
        return mm(nozzle.getLocation()).getZ();
    }

    function goZ(nozzle, value) {
        nozzle.moveTo(new Location(LengthUnit.Millimeters, NaN, NaN, value, NaN), SPEED);
    }

    var head = machine.getDefaultHead();
    var nozzles = head.getNozzles();
    var nozzle = nozzles.get(0);
    if (nozzles.size() > 1) {
        var names = [];
        for (var i = 0; i < nozzles.size(); i++) {
            names.push(String(nozzles.get(i).getName()));
        }
        var pick = ask('Which nozzle should touch the reference?', names.concat(['Cancel']));
        if (pick < 0 || pick >= nozzles.size()) {
            return;
        }
        nozzle = nozzles.get(pick);
    }
    var tip = nozzle.getNozzleTip();
    var tipName = tip === null ? '(no tip)' : String(tip.getName());
    var nozzleName = String(nozzle.getName());

    // Where: the primary calibration fiducial (XY and the Z of its surface).
    var fid = head.getCalibrationPrimaryFiducialLocation();
    if (fid === null || !fid.isInitialized() || (mm(fid).getX() === 0 && mm(fid).getY() === 0)) {
        JOptionPane.showMessageDialog(null, 'The head has no primary calibration fiducial location set.\\n'
            + 'Set it (Machine Setup > Heads > Calibration), then run this again.');
        return;
    }
    var fidMm = mm(fid);
    var startZ = fidMm.getZ() + START_ABOVE_MM;
    var minZ = startZ - MAX_DOWN_MM;
    if (JOptionPane.showConfirmDialog(null, nozzleName + ' with tip ' + tipName + ' will go to X '
        + fidMm.getX().toFixed(2) + ' Y ' + fidMm.getY().toFixed(2) + ' and down to Z ' + startZ.toFixed(2)
        + ' (' + START_ABOVE_MM + ' mm above the fiducial).\\nThe fiducial surface must be clear. Go?',
        'Z reference touch', JOptionPane.OK_CANCEL_OPTION) !== JOptionPane.OK_OPTION) {
        return;
    }
    nozzle.moveToSafeZ();
    nozzle.moveTo(new Location(LengthUnit.Millimeters, fidMm.getX(), fidMm.getY(), NaN, NaN));
    goZ(nozzle, startZ);

    var steps = ['Down 0.5', 'Down 0.1', 'Down 0.02', 'Up 0.1', 'TOUCHING', 'Cancel (go back up)'];
    var delta = [-0.5, -0.1, -0.02, 0.1];
    var touched = false;
    var cur = zOf(nozzle);
    while (true) {
        var c = ask(nozzleName + ' / ' + tipName + '\\nZ now ' + cur.toFixed(3) + ' mm; the fiducial surface is at '
            + fidMm.getZ().toFixed(3) + ' mm.\\nStep down until the tip JUST touches, then press TOUCHING.', steps);
        if (c < 0 || c === 5) {
            break;
        }
        if (c === 4) {
            touched = true;
            break;
        }
        var target = cur + delta[c];
        if (target < minZ) {
            JOptionPane.showMessageDialog(null, 'Stopped: not more than ' + MAX_DOWN_MM + ' mm below the start.');
            continue;
        }
        goZ(nozzle, target);
        cur = zOf(nozzle);
    }
    var touchZ = cur;
    nozzle.moveToSafeZ();
    if (!touched) {
        return;
    }

    // read the log
    var log = new File(java.lang.System.getProperty('user.home'), '.openpnp2/z_reference_log.csv');
    var rows = [];
    if (log.isFile()) {
        var lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        for (var k = 0; k < lines.size(); k++) {
            var f = String(lines.get(k)).split(',');
            if (f.length >= 5 && f[0] !== 'date') {
                rows.push({date: f[0], nozzle: f[1], tip: f[2], z: parseFloat(f[3]), ref: f[4] === '1'});
            }
        }
    }
    var ref = null;
    rows.forEach(function (r) { if (r.ref) { ref = r; } });
    var makeRef = false;
    if (ref === null) {
        makeRef = true;
    } else if (!(ref.nozzle === nozzleName && ref.tip === tipName)) {
        makeRef = ask(nozzleName + ' / ' + tipName + ' touched at Z ' + touchZ.toFixed(3) + '.\\nThe reference (primary truth) is '
            + ref.nozzle + ' / ' + ref.tip + ' (' + ref.z.toFixed(3) + '). Make THIS the new reference?',
            ['No, keep the reference', 'Yes, new reference']) === 1;
    }
    var stamp = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new java.util.Date());
    var entry = {date: stamp, nozzle: nozzleName, tip: tipName, z: touchZ, ref: makeRef};
    var text = stamp + ',' + nozzleName + ',' + tipName + ',' + touchZ.toFixed(3) + ',' + (makeRef ? '1' : '0') + '\\n';
    if (!log.isFile()) {
        text = 'date,nozzle,tip,z_mm,reference\\n' + text;
    }
    log.getParentFile().mkdirs();
    Files.write(log.toPath(), new java.lang.String(text).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    rows.push(entry);
    if (makeRef) {
        ref = entry;
    }

    // latest touch of every nozzle / tip, difference to the reference
    var latest = {};
    rows.forEach(function (r) { latest[r.nozzle + ' / ' + r.tip] = r; });
    var out = 'Reference (primary truth): ' + ref.nozzle + ' / ' + ref.tip + '  Z ' + ref.z.toFixed(3) + '  (' + ref.date + ')\\n\\n';
    Object.keys(latest).sort().forEach(function (key) {
        var r = latest[key];
        var d = r.z - ref.z;
        out += key + ':  Z ' + r.z.toFixed(3) + '   ' + (d >= 0 ? '+' : '') + d.toFixed(3) + ' mm   (' + r.date + ')\\n';
    });
    out += '\\nPositive = touches at a higher Z than the reference (the tip is longer). That number is the "pick Z trim"\\n'
        + 'for the tip in Bus manager > Nozzle pick trim.';
    JOptionPane.showMessageDialog(null, out, 'Z reference touch', JOptionPane.INFORMATION_MESSAGE);
});

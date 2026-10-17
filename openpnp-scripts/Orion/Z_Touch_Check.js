/**
 * Orion Z touch check (by hand): measures where a nozzle tip touches a fixed reference surface, logs it, and shows how
 * far it is from earlier touches of the same tip / nozzle. Use it after a tool change to see whether the tip sits at
 * a different Z. Until there is a sensing station, you are the sensor.
 *
 * Install: copy into %USERPROFILE%\.openpnp2\scripts\Orion\  (Scripts > Refresh Scripts), run from Scripts > Orion.
 *
 * Before you start: put a flat, fixed reference under the nozzle (a metal block or plate that always stays in the same
 * place, same spot every time), jog the nozzle in XY over it and down to about 1-2 mm above it. Then run the script.
 * It asks which nozzle, then you step the nozzle down (big, medium, fine steps) until it just touches (a strip of
 * paper that starts to drag is a good feeler), and press "Touching". Every touch is appended to
 *   %USERPROFILE%\.openpnp2\z_touch_log.csv   (date, nozzle, tip, Z in mm)
 * The FIRST touch of a tip is its reference; later touches are reported relative to it.
 *
 * It moves the machine slowly and never goes more than MAX_DOWN_MM below where you started.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var MAX_DOWN_MM = 6.0;       // never lower than this below the starting Z
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
        return JOptionPane.showOptionDialog(null, msg, 'Z touch check', JOptionPane.DEFAULT_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, Java.to(options, 'java.lang.Object[]'), options[0]);
    }

    function z(nozzle) {
        return nozzle.getLocation().convertToUnits(LengthUnit.Millimeters).getZ();
    }

    function goZ(nozzle, value) {
        nozzle.moveTo(new Location(LengthUnit.Millimeters, NaN, NaN, value, NaN), SPEED);
    }

    var nozzles = machine.getDefaultHead().getNozzles();
    var nozzle = nozzles.get(0);
    if (nozzles.size() > 1) {
        var names = [];
        for (var i = 0; i < nozzles.size(); i++) {
            names.push(String(nozzles.get(i).getName()));
        }
        var pick = ask('Which nozzle is over the reference surface?', names.concat(['Cancel']));
        if (pick < 0 || pick >= nozzles.size()) {
            return;
        }
        nozzle = nozzles.get(pick);
    }
    var tip = nozzle.getNozzleTip();
    var tipName = tip === null ? '(no tip)' : String(tip.getName());
    var nozzleName = String(nozzle.getName());
    var startZ = z(nozzle);
    var minZ = startZ - MAX_DOWN_MM;

    var steps = ['Down 0.5', 'Down 0.1', 'Down 0.02', 'Up 0.1', 'TOUCHING', 'Cancel (go back up)'];
    var delta = [-0.5, -0.1, -0.02, 0.1];
    var touched = false;
    var cur = startZ;
    while (true) {
        var c = ask(nozzleName + ' with tip ' + tipName + '\\nZ now ' + cur.toFixed(3) + ' mm (started at '
            + startZ.toFixed(3) + ').\\nStep down until the tip just touches the surface, then press TOUCHING.', steps);
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
        cur = z(nozzle);
    }
    var touchZ = cur;
    goZ(nozzle, startZ); // back up

    if (!touched) {
        return;
    }

    // log and compare
    var log = new File(java.lang.System.getProperty('user.home'), '.openpnp2/z_touch_log.csv');
    var rows = [];
    if (log.isFile()) {
        var lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8);
        for (var k = 0; k < lines.size(); k++) {
            var f = String(lines.get(k)).split(',');
            if (f.length >= 4 && f[0] !== 'date') {
                rows.push({date: f[0], nozzle: f[1], tip: f[2], z: parseFloat(f[3])});
            }
        }
    }
    var stamp = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss').format(new java.util.Date());
    var line = stamp + ',' + nozzleName + ',' + tipName + ',' + touchZ.toFixed(3) + '\\n';
    if (!log.isFile()) {
        line = 'date,nozzle,tip,z_mm\\n' + line;
    }
    log.getParentFile().mkdirs();
    Files.write(log.toPath(), new java.lang.String(line).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);

    var same = rows.filter(function (r) { return r.nozzle === nozzleName && r.tip === tipName; });
    var msg = nozzleName + ' / tip ' + tipName + ': touched at Z = ' + touchZ.toFixed(3) + ' mm.\\n';
    if (same.length === 0) {
        msg += 'First touch of this tip: stored as its reference.';
    } else {
        var first = same[0].z, last = same[same.length - 1].z;
        msg += 'Reference (first touch, ' + same[0].date + '): ' + first.toFixed(3) + ' mm  ->  '
            + (touchZ - first >= 0 ? '+' : '') + (touchZ - first).toFixed(3) + ' mm\\n';
        msg += 'Last touch (' + same[same.length - 1].date + '): ' + last.toFixed(3) + ' mm  ->  '
            + (touchZ - last >= 0 ? '+' : '') + (touchZ - last).toFixed(3) + ' mm\\n';
        msg += 'Positive = the tip touches at a higher Z than before (sits lower / is longer).';
    }
    var others = {};
    rows.forEach(function (r) {
        if (r.nozzle === nozzleName && r.tip !== tipName && !others[r.tip]) {
            others[r.tip] = r.z;
        }
    });
    var keys = Object.keys(others);
    if (keys.length > 0) {
        msg += '\\n\\nOther tips on this nozzle (reference): ';
        msg += keys.map(function (t) { return t + ' ' + others[t].toFixed(3); }).join(', ');
    }
    JOptionPane.showMessageDialog(null, msg, 'Z touch check', JOptionPane.INFORMATION_MESSAGE);
});

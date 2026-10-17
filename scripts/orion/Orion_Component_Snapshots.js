/**
 * Orion component snapshots: for the board selected in the Job tab (the first one if none is selected), moves the head
 * camera over every placement in turn and saves one picture of each, with a crosshair at the placement position and
 * the designator in the corner. Use it to check where parts really landed.
 *
 * Install: copy this file into  %USERPROFILE%\.openpnp2\scripts\  and run it from the Scripts menu (Scripts > Refresh).
 * Output: a new folder  %USERPROFILE%\.openpnp2\board_snapshots\<board>_<date-time>\  with  001_R1.png, 002_C3.png ...
 * and an index.csv (number, designator, part, machine X, machine Y).
 */

// ---- settings -------------------------------------------------------------------------------------------------
var INCLUDE_FIDUCIALS = true;   // also take pictures of the fiducial placements
var ONLY_ENABLED = true;        // skip disabled placements
var ONLY_BOARD_SIDE = true;     // only placements on the side of the board that is up
var CAMERA_Z = null;            // null = the camera's default Z; or a number (mm)
var CROSSHAIR = true;           // draw a cross at the placement position and the designator
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

var imports = new JavaImporter(org.openpnp.model, org.openpnp.util, java.awt, java.awt.image, java.io, javax.imageio);

function log(msg) {
    Packages.org.pmw.tinylog.Logger.info('Component snapshots: ' + msg);
    print('Component snapshots: ' + msg);
}

with (imports) {
    task(function () {
        var camera = machine.getDefaultHead().getDefaultCamera();
        var bl = gui.getJobTab().getSelection();
        if (bl === null) {
            var locs = gui.getJobTab().getJob().getBoardAndPanelLocations();
            if (locs.size() < 1) {
                throw new Error('The job has no board.');
            }
            bl = locs.get(0);
            log('nothing selected in the Job tab, using the first one');
        }
        var holder = bl.getPlacementsHolder();
        var name = String(holder.getName() || 'board').replace(/[^A-Za-z0-9_-]/g, '_');
        var z = CAMERA_Z !== null ? CAMERA_Z
            : camera.getDefaultZ().convertToUnits(LengthUnit.Millimeters).getValue();
        var upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);

        // Collect the placements and their machine positions.
        var items = [];
        var pls = holder.getPlacements();
        for (var i = 0; i < pls.size(); i++) {
            var pl = pls.get(i);
            if (ONLY_ENABLED && !pl.isEnabled()) continue;
            if (!INCLUDE_FIDUCIALS && pl.getType() == Placement.Type.Fiducial) continue;
            if (ONLY_BOARD_SIDE && pl.getSide() != bl.getGlobalSide()) continue;
            var loc = Utils2D.calculateBoardPlacementLocation(bl, pl.getLocation())
                .convertToUnits(LengthUnit.Millimeters);
            items.push({
                id: String(pl.getId()),
                part: pl.getPart() === null ? '' : String(pl.getPart().getId()),
                x: loc.getX(),
                y: loc.getY()
            });
        }
        if (items.length < 1) {
            throw new Error('No placements to photograph (check ONLY_ENABLED / ONLY_BOARD_SIDE).');
        }

        // Short travel: nearest neighbour from where the camera is now.
        var cur = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        var cx = cur.getX(), cy = cur.getY();
        var order = [];
        while (items.length > 0) {
            var best = 0, bestD = Infinity;
            for (var k = 0; k < items.length; k++) {
                var d = Math.pow(items[k].x - cx, 2) + Math.pow(items[k].y - cy, 2);
                if (d < bestD) { bestD = d; best = k; }
            }
            var it = items.splice(best, 1)[0];
            order.push(it);
            cx = it.x; cy = it.y;
        }

        var stamp = new java.text.SimpleDateFormat('yyyyMMdd-HHmmss').format(new java.util.Date());
        var dir = new File(java.lang.System.getProperty('user.home'), '.openpnp2/board_snapshots/' + name + '_' + stamp);
        dir.mkdirs();
        var csv = new java.lang.StringBuilder('number,designator,part,x_mm,y_mm\n');
        log(order.length + ' pictures into ' + dir.getAbsolutePath());

        for (var n = 0; n < order.length; n++) {
            var o = order[n];
            var where = new Location(LengthUnit.Millimeters, o.x, o.y, z, camera.getLocation().getRotation());
            MovableUtils.moveToLocationAtSafeZ(camera, where);
            var img = camera.lightSettleAndCapture();
            if (CROSSHAIR) {
                var g = img.createGraphics();
                var w = img.getWidth(), h = img.getHeight();
                // The placement is at the image centre (the camera was moved there).
                g.setColor(Color.RED);
                g.drawLine(w / 2 - 25, h / 2, w / 2 + 25, h / 2);
                g.drawLine(w / 2, h / 2 - 25, w / 2, h / 2 + 25);
                g.setFont(new Font('SansSerif', Font.BOLD, 28));
                g.drawString(o.id + (o.part ? '  ' + o.part : ''), 14, 36);
                g.dispose();
            }
            var num = ('000' + (n + 1)).slice(-3);
            ImageIO.write(img, 'png', new File(dir, num + '_' + o.id.replace(/[^A-Za-z0-9_-]/g, '_') + '.png'));
            csv.append(num + ',' + o.id + ',' + o.part + ',' + o.x.toFixed(3) + ',' + o.y.toFixed(3) + '\n');
            log((n + 1) + ' of ' + order.length + ': ' + o.id);
        }
        java.nio.file.Files.write(new File(dir, 'index.csv').toPath(),
            csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        log('done: ' + dir.getAbsolutePath());
    });
}

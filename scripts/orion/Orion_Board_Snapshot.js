/**
 * Orion board snapshot: drives the head camera over the board selected in the Job tab (the first one if none is selected, or a rectangle you give below),
 * takes overlapping pictures and stitches their centres into one image of the whole board, for diagnostics
 * (placement accuracy, what is where, fiducials, solder paste ...).
 *
 * Install: copy this file into  %USERPROFILE%\.openpnp2\scripts\  and run it from the Scripts menu (Scripts > Refresh).
 * Output: %USERPROFILE%\.openpnp2\board_snapshots\<board>_<date-time>.png  (path is shown in the log).
 *
 * The stitching does not blend: each picture contributes only its centre, so the result is only as good as the camera
 * calibration (units per pixel). A seam that jumps = calibration or backlash problem, which is the point.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var MARGIN_MM = 2.0;      // extra border around the board
var OVERLAP = 0.15;       // fraction of the picture that is neighbour overlap (the overlap edge is cut away)
var SCALE = 0.5;          // output scale: 1.0 = full camera resolution (can be very large), 0.5 = half
var MANUAL = null;        // or {x0: 10, y0: 10, x1: 110, y1: 90} in machine mm instead of the job's board
var CAMERA_Z = null;      // null = the camera's default Z; or a number (mm)
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

var imports = new JavaImporter(org.openpnp.model, org.openpnp.util, java.awt, java.awt.image, java.io, javax.imageio);

function log(msg) {
    Packages.org.pmw.tinylog.Logger.info('Board snapshot: ' + msg);
    print('Board snapshot: ' + msg);
}

with (imports) {
    task(function () {
        var camera = machine.getDefaultHead().getDefaultCamera();
        var name = 'board';
        var x0, y0, x1, y1;

        if (MANUAL) {
            x0 = MANUAL.x0; y0 = MANUAL.y0; x1 = MANUAL.x1; y1 = MANUAL.y1;
        } else {
            // The board (or panel) selected in the Job tab; the first one of the job if nothing is selected.
            var bl = gui.getJobTab().getSelection();
            if (bl === null) {
                var locs = gui.getJobTab().getJob().getBoardAndPanelLocations();
                if (locs.size() < 1) {
                    throw new Error('The job has no board. Add a board to the job, or set MANUAL in the script.');
                }
                bl = locs.get(0);
                log('nothing selected in the Job tab, using the first one');
            }
            name = String(bl.getPlacementsHolder().getName() || 'board').replace(/[^A-Za-z0-9_-]/g, '_');
            var dim = bl.getPlacementsHolder().getDimensions().convertToUnits(LengthUnit.Millimeters);
            if (dim.getX() <= 0 || dim.getY() <= 0) {
                throw new Error('The board has no dimensions set. Set them on the board, or use MANUAL.');
            }
            // The four corners in machine coordinates (handles rotation and flipping of the board location).
            var xs = [], ys = [];
            var corners = [[0, 0], [dim.getX(), 0], [0, dim.getY()], [dim.getX(), dim.getY()]];
            for (var i = 0; i < 4; i++) {
                var c = Utils2D.calculateBoardPlacementLocation(bl,
                    new Location(LengthUnit.Millimeters, corners[i][0], corners[i][1], 0, 0))
                    .convertToUnits(LengthUnit.Millimeters);
                xs.push(c.getX());
                ys.push(c.getY());
            }
            x0 = Math.min.apply(null, xs); x1 = Math.max.apply(null, xs);
            y0 = Math.min.apply(null, ys); y1 = Math.max.apply(null, ys);
        }
        x0 -= MARGIN_MM; y0 -= MARGIN_MM; x1 += MARGIN_MM; y1 += MARGIN_MM;

        var z = CAMERA_Z !== null ? CAMERA_Z
            : camera.getDefaultZ().convertToUnits(LengthUnit.Millimeters).getValue();
        var upp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        var uppX = upp.getX(), uppY = upp.getY();
        var fovX = camera.getWidth() * uppX, fovY = camera.getHeight() * uppY;
        var stepX = fovX * (1 - OVERLAP), stepY = fovY * (1 - OVERLAP);
        var cols = Math.max(1, Math.ceil((x1 - x0) / stepX));
        var rows = Math.max(1, Math.ceil((y1 - y0) / stepY));
        log('area X ' + x0.toFixed(1) + '..' + x1.toFixed(1) + ' Y ' + y0.toFixed(1) + '..' + y1.toFixed(1)
            + ' mm, ' + cols + ' x ' + rows + ' pictures (field of view ' + fovX.toFixed(1) + ' x '
            + fovY.toFixed(1) + ' mm)');

        var outW = Math.ceil((x1 - x0) / uppX * SCALE);
        var outH = Math.ceil((y1 - y0) / uppY * SCALE);
        var out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, outW, outH);

        // The grid is centred on the area; serpentine order to keep travel short.
        var gridW = cols * stepX, gridH = rows * stepY;
        var gx0 = x0 + ((x1 - x0) - gridW) / 2, gy0 = y0 + ((y1 - y0) - gridH) / 2;
        var n = 0;
        for (var r = 0; r < rows; r++) {
            for (var k = 0; k < cols; k++) {
                var cc = (r % 2 === 0) ? k : cols - 1 - k;
                var cx = gx0 + (cc + 0.5) * stepX;
                var cy = gy0 + (r + 0.5) * stepY;
                var where = new Location(LengthUnit.Millimeters, cx, cy, z, camera.getLocation().getRotation());
                MovableUtils.moveToLocationAtSafeZ(camera, where);
                var img = camera.lightSettleAndCapture();
                // Centre part of the picture, step size, in picture pixels.
                var sw = stepX / uppX, sh = stepY / uppY;
                var sx = (img.getWidth() - sw) / 2, sy = (img.getHeight() - sh) / 2;
                // Destination: world (cx +- step/2, cy +- step/2); picture top = larger Y.
                var dx = (cx - stepX / 2 - x0) / uppX * SCALE;
                var dy = (y1 - (cy + stepY / 2)) / uppY * SCALE;
                g.drawImage(img, Math.round(dx), Math.round(dy), Math.round(dx + sw * SCALE),
                    Math.round(dy + sh * SCALE), Math.round(sx), Math.round(sy), Math.round(sx + sw),
                    Math.round(sy + sh), null);
                n++;
                log('picture ' + n + ' of ' + (cols * rows));
            }
        }
        g.dispose();

        var dir = new File(System.getProperty('user.home'), '.openpnp2/board_snapshots');
        dir.mkdirs();
        var stamp = new java.text.SimpleDateFormat('yyyyMMdd-HHmmss').format(new java.util.Date());
        var file = new File(dir, name + '_' + stamp + '.png');
        ImageIO.write(out, 'png', file);
        log('saved ' + file.getAbsolutePath() + ' (' + outW + ' x ' + outH + ' px, ' + (1 / (SCALE / uppX)).toFixed(4)
            + ' mm/px, origin = top-left = X ' + x0.toFixed(2) + ' Y ' + y1.toFixed(2) + ')');
    });
}

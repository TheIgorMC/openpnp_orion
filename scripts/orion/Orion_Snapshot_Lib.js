/**
 * Shared code of the Orion snapshot scripts (Orion_Board_Snapshot.js, Orion_Component_Snapshots.js,
 * Orion_Snapshots_All_Boards.js and Events/Job.Finished.Orion_Snapshots.js). Copy it into the same folder as them:
 * %USERPROFILE%\.openpnp2\scripts\   It does nothing by itself.
 *
 * Everything here must run as a machine task (the scripts that load it take care of that).
 */
var OrionSnap = (function () {
    var File = Java.type('java.io.File');
    var ImageIO = Java.type('javax.imageio.ImageIO');
    var BufferedImage = Java.type('java.awt.image.BufferedImage');
    var Color = Java.type('java.awt.Color');
    var Font = Java.type('java.awt.Font');
    var RenderingHints = Java.type('java.awt.RenderingHints');
    var JSystem = Java.type('java.lang.System');
    var Location = Java.type('org.openpnp.model.Location');
    var Length = Java.type('org.openpnp.model.Length');
    var LengthUnit = Java.type('org.openpnp.model.LengthUnit');
    var Placement = Java.type('org.openpnp.model.Placement');
    var Utils2D = Java.type('org.openpnp.util.Utils2D');
    var MovableUtils = Java.type('org.openpnp.util.MovableUtils');
    var OpenCvUtils = Java.type('org.openpnp.util.OpenCvUtils');
    var Mat = Java.type('org.opencv.core.Mat');
    var CvType = Java.type('org.opencv.core.CvType');
    var Rect = Java.type('org.opencv.core.Rect');
    var Imgproc = Java.type('org.opencv.imgproc.Imgproc');
    var Logger = Java.type('org.pmw.tinylog.Logger');

    function log(msg) {
        Logger.info('Snapshot: ' + msg);
        print('Snapshot: ' + msg);
    }

    function stamp() {
        return new java.text.SimpleDateFormat('yyyyMMdd-HHmmss').format(new java.util.Date());
    }

    function safe(s) {
        return String(s).replace(/[^A-Za-z0-9_-]/g, '_');
    }

    function rootDir() {
        return new File(JSystem.getProperty('user.home'), '.openpnp2/board_snapshots');
    }

    function mm(location) {
        return location.convertToUnits(LengthUnit.Millimeters);
    }

    function camera() {
        return machine.getDefaultHead().getDefaultCamera();
    }

    /** The board (or panel) selected in the Job tab, the first one of the job if nothing is selected. */
    function selectedOrFirst() {
        var bl = gui.getJobTab().getSelection();
        if (bl === null) {
            var locs = gui.getJobTab().getJob().getBoardAndPanelLocations();
            if (locs.size() < 1) {
                throw new Error('The job has no board.');
            }
            bl = locs.get(0);
            log('nothing selected in the Job tab, using the first one');
        }
        return bl;
    }

    /** All boards of a job as a JS array (panels are expanded into their boards). */
    function boardsOf(theJob) {
        var list = theJob.getBoardLocations();
        var out = [];
        for (var i = 0; i < list.size(); i++) {
            out.push(list.get(i));
        }
        return out;
    }

    function holderName(bl) {
        return safe(bl.getPlacementsHolder().getName() || 'board');
    }

    // ---------------------------------------------------------------------------------------- picture helpers

    /** The part of the picture that is not black border (found on the middle row and column), with a margin. */
    function usableRect(img) {
        var w = img.getWidth(), h = img.getHeight();
        var T = 3 * 14;
        function lum(x, y) {
            var p = img.getRGB(x, y);
            return ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255);
        }
        var cx = w >> 1, cy = h >> 1;
        var l = 0; while (l < cx && lum(l, cy) < T) l++;
        var r = w - 1; while (r > cx && lum(r, cy) < T) r--;
        var t = 0; while (t < cy && lum(cx, t) < T) t++;
        var b = h - 1; while (b > cy && lum(cx, b) < T) b--;
        var m = Math.round(0.02 * Math.min(w, h));
        return {x: l + m, y: t + m, w: (r - l + 1) - 2 * m, h: (b - t + 1) - 2 * m};
    }

    /**
     * Real mm per pixel: pictures with the camera moved by known distances, how far the picture moved (phase
     * correlation on the usable part). Returns {x, y} or null.
     */
    function measureScale(cam, cx, cy, z, base, valid) {
        var rot = cam.getLocation().getRotation();
        function gray(img) {
            var bgr = OpenCvUtils.toMat(img);
            var gr = new Mat();
            Imgproc.cvtColor(bgr, gr, Imgproc.COLOR_BGR2GRAY);
            var f = new Mat();
            gr.convertTo(f, CvType.CV_32F);
            var sub = f.submat(new Rect(valid.x, valid.y, valid.w, valid.h)).clone();
            bgr.release(); gr.release(); f.release();
            return sub;
        }
        function shot(x, y) {
            MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, x, y, z, rot));
            return gray(cam.lightSettleAndCapture());
        }
        var DX = 2.0, DY = 1.5; // mm, small compared with the field of view
        var a = gray(base);
        var bx = shot(cx + DX, cy);
        var sx = Imgproc.phaseCorrelate(a, bx);
        var by = shot(cx, cy + DY);
        var sy = Imgproc.phaseCorrelate(a, by);
        a.release(); bx.release(); by.release();
        var px = Math.abs(sx.x), py = Math.abs(sy.y);
        if (px < 5 || py < 5 || Math.abs(sx.y) > px * 0.2 || Math.abs(sy.x) > py * 0.2) {
            return null; // the picture did not move along the expected axis: no reliable measurement
        }
        return {x: DX / px, y: DY / py};
    }

    // ---------------------------------------------------------------------------------------- whole board

    /**
     * One stitched picture of the board.
     * o: {marginMm, extentMarginMm, overlap, scale, manual, cameraZ, subjectZ, measureScale}
     * dir: folder to write into (a File), file name  <board>_<time>.png  Returns the file.
     */
    function board(bl, o, dir) {
        var cam = camera();
        var name = 'board';
        var x0, y0, x1, y1;
        if (o.manual) {
            x0 = o.manual.x0; y0 = o.manual.y0; x1 = o.manual.x1; y1 = o.manual.y1;
            bl = null;
        } else {
            name = holderName(bl);
            var xs = [], ys = [];
            var dim = mm(bl.getPlacementsHolder().getDimensions());
            if (dim.getX() > 0 && dim.getY() > 0) {
                var corners = [[0, 0], [dim.getX(), 0], [0, dim.getY()], [dim.getX(), dim.getY()]];
                for (var i = 0; i < 4; i++) {
                    var c = mm(Utils2D.calculateBoardPlacementLocation(bl,
                        new Location(LengthUnit.Millimeters, corners[i][0], corners[i][1], 0, 0)));
                    xs.push(c.getX());
                    ys.push(c.getY());
                }
            } else {
                var pls = bl.getPlacementsHolder().getPlacements();
                for (var j = 0; j < pls.size(); j++) {
                    var q = mm(Utils2D.calculateBoardPlacementLocation(bl, pls.get(j).getLocation()));
                    xs.push(q.getX());
                    ys.push(q.getY());
                }
                if (xs.length < 1) {
                    throw new Error('Board ' + name + ' has no dimensions and no placements.');
                }
                log(name + ': no board dimensions, using the extent of ' + xs.length + ' placements + '
                    + o.extentMarginMm + ' mm');
                xs.push(Math.min.apply(null, xs) - o.extentMarginMm, Math.max.apply(null, xs) + o.extentMarginMm);
                ys.push(Math.min.apply(null, ys) - o.extentMarginMm, Math.max.apply(null, ys) + o.extentMarginMm);
            }
            x0 = Math.min.apply(null, xs); x1 = Math.max.apply(null, xs);
            y0 = Math.min.apply(null, ys); y1 = Math.max.apply(null, ys);
        }
        x0 -= o.marginMm; y0 -= o.marginMm; x1 += o.marginMm; y1 += o.marginMm;

        var z = o.cameraZ !== null ? o.cameraZ : mm(cam.getDefaultZ()).getValue();
        var rot = cam.getLocation().getRotation();
        var upp;
        if (o.subjectZ !== null) {
            upp = cam.getUnitsPerPixel(new Length(o.subjectZ, LengthUnit.Millimeters));
        } else if (bl !== null) {
            upp = cam.getUnitsPerPixel(bl.getLocation().getLengthZ());
        } else {
            upp = cam.getUnitsPerPixelAtZ();
        }
        upp = upp.convertToUnits(LengthUnit.Millimeters);
        var uppX = upp.getX(), uppY = upp.getY();

        // Test picture at the centre: usable part (no black border) and, optionally, the real scale.
        MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, (x0 + x1) / 2, (y0 + y1) / 2,
            z, rot));
        var test = cam.lightSettleAndCapture();
        var valid = usableRect(test);
        if (o.measureScale) {
            var m = measureScale(cam, (x0 + x1) / 2, (y0 + y1) / 2, z, test, valid);
            if (m !== null) {
                log(name + ': measured scale ' + m.x.toFixed(4) + ' x ' + m.y.toFixed(4) + ' mm/px (calibration said '
                    + uppX.toFixed(4) + ' x ' + uppY.toFixed(4) + ')');
                uppX = m.x;
                uppY = m.y;
            } else {
                log(name + ': could not measure the scale, using the calibration ' + uppX.toFixed(4) + ' mm/px');
            }
        }
        var fovX = valid.w * uppX, fovY = valid.h * uppY;
        var cols = Math.max(1, Math.ceil((x1 - x0) / (fovX * (1 - o.overlap))));
        var rows = Math.max(1, Math.ceil((y1 - y0) / (fovY * (1 - o.overlap))));
        var stepX = (x1 - x0) / cols, stepY = (y1 - y0) / rows;
        log(name + ': area X ' + x0.toFixed(1) + '..' + x1.toFixed(1) + ' Y ' + y0.toFixed(1) + '..' + y1.toFixed(1)
            + ' mm, ' + cols + ' x ' + rows + ' pictures');

        var outW = Math.ceil((x1 - x0) / uppX * o.scale);
        var outH = Math.ceil((y1 - y0) / uppY * o.scale);
        var out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, outW, outH);
        var n = 0;
        for (var r = 0; r < rows; r++) {
            for (var k = 0; k < cols; k++) {
                var cc = (r % 2 === 0) ? k : cols - 1 - k;
                var cx = x0 + (cc + 0.5) * stepX;
                var cy = y0 + (r + 0.5) * stepY;
                MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, cx, cy, z, rot));
                var img = cam.lightSettleAndCapture();
                var sw = stepX / uppX, sh = stepY / uppY;
                var sx = valid.x + (valid.w - sw) / 2, sy = valid.y + (valid.h - sh) / 2;
                var dx = (cx - stepX / 2 - x0) / uppX * o.scale;
                var dy = (y1 - (cy + stepY / 2)) / uppY * o.scale;
                g.drawImage(img, Math.round(dx), Math.round(dy), Math.round(dx + sw * o.scale),
                    Math.round(dy + sh * o.scale), Math.round(sx), Math.round(sy), Math.round(sx + sw),
                    Math.round(sy + sh), null);
                n++;
                log(name + ': picture ' + n + ' of ' + (cols * rows));
            }
        }
        g.dispose();
        dir.mkdirs();
        var file = new File(dir, name + '_' + stamp() + '.png');
        ImageIO.write(out, 'png', file);
        log('saved ' + file.getAbsolutePath() + ' (' + outW + ' x ' + outH + ' px, origin top-left = X '
            + x0.toFixed(2) + ' Y ' + y1.toFixed(2) + ')');
        return file;
    }

    // ---------------------------------------------------------------------------------------- per component

    /**
     * One picture per placement, into dir (a File, created). o: {includeFiducials, onlyEnabled, onlyBoardSide,
     * cameraZ, crosshair}. Returns the folder.
     */
    function components(bl, o, dir) {
        var cam = camera();
        var holder = bl.getPlacementsHolder();
        var z = o.cameraZ !== null ? o.cameraZ : mm(cam.getDefaultZ()).getValue();
        var rot = cam.getLocation().getRotation();
        var items = [];
        var pls = holder.getPlacements();
        for (var i = 0; i < pls.size(); i++) {
            var pl = pls.get(i);
            if (o.onlyEnabled && !pl.isEnabled()) continue;
            if (!o.includeFiducials && pl.getType() == Placement.Type.Fiducial) continue;
            if (o.onlyBoardSide && pl.getSide() != bl.getGlobalSide()) continue;
            var loc = mm(Utils2D.calculateBoardPlacementLocation(bl, pl.getLocation()));
            items.push({
                id: String(pl.getId()),
                part: pl.getPart() === null ? '' : String(pl.getPart().getId()),
                x: loc.getX(),
                y: loc.getY()
            });
        }
        if (items.length < 1) {
            log(holderName(bl) + ': no placements to photograph');
            return dir;
        }
        var cur = mm(cam.getLocation());
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
        dir.mkdirs();
        var csv = new java.lang.StringBuilder('number,designator,part,x_mm,y_mm\n');
        log(holderName(bl) + ': ' + order.length + ' pictures into ' + dir.getAbsolutePath());
        for (var n = 0; n < order.length; n++) {
            var p = order[n];
            MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, p.x, p.y, z, rot));
            var img = cam.lightSettleAndCapture();
            if (o.crosshair) {
                var g = img.createGraphics();
                var w = img.getWidth(), h = img.getHeight();
                g.setColor(Color.RED);
                g.drawLine(w / 2 - 25, h / 2, w / 2 + 25, h / 2);
                g.drawLine(w / 2, h / 2 - 25, w / 2, h / 2 + 25);
                g.setFont(new Font('SansSerif', Font.BOLD, 28));
                g.drawString(p.id + (p.part ? '  ' + p.part : ''), 14, 36);
                g.dispose();
            }
            var num = ('000' + (n + 1)).slice(-3);
            ImageIO.write(img, 'png', new File(dir, num + '_' + safe(p.id) + '.png'));
            csv.append(num + ',' + p.id + ',' + p.part + ',' + p.x.toFixed(3) + ',' + p.y.toFixed(3) + '\n');
            log(holderName(bl) + ': ' + (n + 1) + ' of ' + order.length + ': ' + p.id);
        }
        java.nio.file.Files.write(new File(dir, 'index.csv').toPath(),
            csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }

    /**
     * The same snapshot of every board of the job. cfg: {mode: 'board' | 'components' | 'both', board: {...},
     * components: {...}}. One folder per run, one sub-folder per board.
     */
    function runAll(theJob, cfg) {
        var jobName = 'job';
        try {
            var f = theJob.getFile();
            if (f !== null) {
                jobName = safe(String(f.getName()).replace(/\.[^.]*$/, ''));
            }
        } catch (e) {
            // unnamed job
        }
        var parent = new File(rootDir(), jobName + '_' + stamp());
        var boards = boardsOf(theJob);
        log('job ' + jobName + ': ' + boards.length + ' boards, ' + cfg.mode + ', into ' + parent.getAbsolutePath());
        for (var i = 0; i < boards.length; i++) {
            var bl = boards[i];
            var sub = new File(parent, ('00' + (i + 1)).slice(-2) + '_' + holderName(bl));
            try {
                if (cfg.mode === 'board' || cfg.mode === 'both') {
                    board(bl, cfg.board, sub);
                }
                if (cfg.mode === 'components' || cfg.mode === 'both') {
                    components(bl, cfg.components, new File(sub, 'components'));
                }
            } catch (e) {
                // One board failing must not stop the others (or the end of a job).
                log('board ' + holderName(bl) + ' failed: ' + e);
            }
        }
        log('all boards done: ' + parent.getAbsolutePath());
        return parent;
    }

    return {
        runAll: runAll, log: log, stamp: stamp, safe: safe, rootDir: rootDir, selectedOrFirst: selectedOrFirst, boardsOf: boardsOf,
        holderName: holderName, board: board, components: components,
        File: File
    };
})();

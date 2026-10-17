/**
 * Shared code of the Orion snapshot scripts (Snapshot_Before / During / After.js and the Job.* event scripts). It lives in
 * %USERPROFILE%\.openpnp2\scripts\Orion\  and does nothing by itself.
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
    var ImageWriteParam = Java.type('javax.imageio.ImageWriteParam');
    var IIOImage = Java.type('javax.imageio.IIOImage');

    // The last measured picture scale {z, x, y} (mm per pixel), reused for the close-up crops of the same run.
    var cachedScale = null;

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

    /** Write an image as png, or as jpg with the given quality (0..1). */
    function writeImage(img, file, format, quality) {
        if (format === 'jpg') {
            var writer = ImageIO.getImageWritersByFormatName('jpg').next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            var ios = ImageIO.createImageOutputStream(file);
            try {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                ios.close();
                writer.dispose();
            }
        } else {
            ImageIO.write(img, 'png', file);
        }
    }

    /**
     * Half the size {hx, hy} (mm, along machine X and Y) of the footprint of a placement's package (body and pads)
     * around the placement position, for its final rotation in degrees. null if the package has no footprint size.
     */
    function halfExtentsMm(pl, rotationDeg) {
        try {
            var part = pl.getPart();
            if (part === null || part.getPackage() === null || part.getPackage().getFootprint() === null) {
                return null;
            }
            var fp = part.getPackage().getFootprint();
            var b = fp.getShape().getBounds2D();
            if (b.getWidth() <= 0 || b.getHeight() <= 0) {
                return null;
            }
            var u = fp.getUnits();
            function toMm(v) {
                return new Length(v, u).convertToUnits(LengthUnit.Millimeters).getValue();
            }
            var xs = [toMm(b.getMinX()), toMm(b.getMaxX())], ys = [toMm(b.getMinY()), toMm(b.getMaxY())];
            var th = rotationDeg * Math.PI / 180, c = Math.cos(th), sn = Math.sin(th);
            var hx = 0, hy = 0;
            for (var i = 0; i < 2; i++) {
                for (var j = 0; j < 2; j++) {
                    hx = Math.max(hx, Math.abs(xs[i] * c - ys[j] * sn));
                    hy = Math.max(hy, Math.abs(xs[i] * sn + ys[j] * c));
                }
            }
            return {hx: hx, hy: hy};
        } catch (e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------- whole board

    /**
     * One stitched picture of the board.
     * o: {marginMm, extentMarginMm, overlap, scale, manual, cameraZ, subjectZ, measureScale}
     * dir: folder to write into (a File); file  <fileBase>.png  (or <board>_<time>.png). Returns the file.
     */
    function board(bl, o, dir, fileBase) {
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
        cachedScale = {z: z, x: uppX, y: uppY};
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
        var file = new File(dir, (fileBase ? fileBase : name + '_' + stamp()) + '.png');
        ImageIO.write(out, 'png', file);
        log('saved ' + file.getAbsolutePath() + ' (' + outW + ' x ' + outH + ' px, origin top-left = X '
            + x0.toFixed(2) + ' Y ' + y1.toFixed(2) + ')');
        return file;
    }

    // ---------------------------------------------------------------------------------------- per component

    /**
     * One close-up per placement, into dir (a File, created), cropped to the part's footprint + cropMarginMm each side
     * (fallbackMm square if the package has no footprint). o: {includeFiducials, onlyEnabled, onlyBoardSide, cameraZ,
     * crosshair, crop (default true), cropMarginMm (2), fallbackMm (6), format 'jpg' | 'png', jpgQuality (90)}.
     * Returns the folder.
     */
    function components(bl, o, dir, suffix) {
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
                y: loc.getY(),
                ext: halfExtentsMm(pl, loc.getRotation())
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
        var csv = new java.lang.StringBuilder('number,designator,part,x_mm,y_mm,crop_mm\n');
        var fmt = o.format === 'jpg' ? 'jpg' : 'png';
        var ext = fmt === 'jpg' ? '.jpg' : '.png';
        var quality = (o.jpgQuality || 90) / 100;
        var cropMargin = o.cropMarginMm === undefined ? 2.0 : o.cropMarginMm;
        var fallbackMm = o.fallbackMm || 6.0;
        var scale = (o.crop !== false) ? null : undefined; // undefined: no cropping at all
        log(holderName(bl) + ': ' + order.length + ' pictures into ' + dir.getAbsolutePath());
        for (var n = 0; n < order.length; n++) {
            var p = order[n];
            MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, p.x, p.y, z, rot));
            var img = cam.lightSettleAndCapture();
            var cropNote = 'full';
            if (scale !== undefined) {
                if (scale === null) {
                    // First picture of the run: the real mm per pixel (cached from a panorama of the same Z).
                    if (cachedScale !== null && Math.abs(cachedScale.z - z) < 1e-6) {
                        scale = cachedScale;
                    } else {
                        var vr = usableRect(img);
                        var ms = measureScale(cam, p.x, p.y, z, img, vr);
                        if (ms !== null) {
                            scale = {z: z, x: ms.x, y: ms.y};
                            log('close-ups: measured scale ' + ms.x.toFixed(4) + ' x ' + ms.y.toFixed(4) + ' mm/px');
                        } else {
                            var cu = mm(cam.getUnitsPerPixel(bl.getLocation().getLengthZ()));
                            scale = {z: z, x: cu.getX(), y: cu.getY()};
                            log('close-ups: could not measure the scale, using the calibration');
                        }
                        cachedScale = scale;
                        // The measurement moved the camera: go back to the placement and take the picture again.
                        MovableUtils.moveToLocationAtSafeZ(cam, new Location(LengthUnit.Millimeters, p.x, p.y, z,
                            rot));
                        img = cam.lightSettleAndCapture();
                    }
                }
                var hxMm = p.ext ? p.ext.hx + cropMargin : fallbackMm / 2;
                var hyMm = p.ext ? p.ext.hy + cropMargin : fallbackMm / 2;
                var halfW = Math.round(hxMm / scale.x), halfH = Math.round(hyMm / scale.y);
                var vrc = usableRect(img);
                var ccx = img.getWidth() >> 1, ccy = img.getHeight() >> 1;
                var x0p = Math.max(vrc.x, ccx - halfW), x1p = Math.min(vrc.x + vrc.w, ccx + halfW);
                var y0p = Math.max(vrc.y, ccy - halfH), y1p = Math.min(vrc.y + vrc.h, ccy + halfH);
                if (x1p - x0p > 8 && y1p - y0p > 8) {
                    img = img.getSubimage(x0p, y0p, x1p - x0p, y1p - y0p);
                    cropNote = ((x1p - x0p) * scale.x).toFixed(1) + 'x' + ((y1p - y0p) * scale.y).toFixed(1);
                }
            }
            if (o.crosshair) {
                var g = img.createGraphics();
                var w = img.getWidth(), h = img.getHeight();
                g.setColor(Color.RED);
                g.drawLine(w / 2 - 25, h / 2, w / 2 + 25, h / 2);
                g.drawLine(w / 2, h / 2 - 25, w / 2, h / 2 + 25);
                g.setFont(new Font('SansSerif', Font.BOLD, 20));
                g.drawString(p.id + (p.part ? '  ' + p.part : ''), 8, 24);
                g.dispose();
            }
            var num = ('000' + (n + 1)).slice(-3);
            writeImage(img, new File(dir, safe(p.id) + (suffix ? '_' + suffix : '') + ext), fmt, quality);
            csv.append(num + ',' + p.id + ',' + p.part + ',' + p.x.toFixed(3) + ',' + p.y.toFixed(3) + ',' + cropNote
                + '\n');
            log(holderName(bl) + ': ' + (n + 1) + ' of ' + order.length + ': ' + p.id);
        }
        java.nio.file.Files.write(new File(dir, 'index' + (suffix ? '_' + suffix : '') + '.csv').toPath(),
            csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }

    // ---------------------------------------------------------------------------------------- montages

    var STAGE_NAMES = ['before', 'during', 'after'];

    function imageIn(dir, base) {
        var exts = ['.png', '.jpg'];
        for (var i = 0; i < exts.length; i++) {
            var f = new File(dir, base + exts[i]);
            if (f.isFile()) {
                try {
                    return ImageIO.read(f);
                } catch (e) {
                    return null;
                }
            }
        }
        return null;
    }

    function fit(g, img, x, y, w, h) {
        var sc = Math.min(w / img.getWidth(), h / img.getHeight());
        var dw = Math.max(1, Math.round(img.getWidth() * sc)), dh = Math.max(1, Math.round(img.getHeight() * sc));
        g.drawImage(img, x + Math.round((w - dw) / 2), y + Math.round((h - dh) / 2), dw, dh, null);
    }

    function newCanvas(w, h) {
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, w, h);
        return {img: out, g: g};
    }

    function subDirs(dir) {
        var names = dir.list();
        var out = [];
        for (var i = 0; names !== null && i < names.length; i++) {
            var d = new File(dir, names[i]);
            if (d.isDirectory()) {
                out.push(d);
            }
        }
        out.sort(function (a, b) { return String(a.getName()).localeCompare(String(b.getName())); });
        return out;
    }

    /**
     * overview.jpg in the batch folder: the panoramas of every board, one row per board, one column per stage
     * (before / during / after) that exists. Returns the file or null.
     */
    function overview(batch, o) {
        var cellW = (o && o.cellWidth) || 800;
        var rows = [];
        var used = {};
        var boards = subDirs(batch);
        for (var i = 0; i < boards.length; i++) {
            var imgs = {}, any = false;
            for (var k = 0; k < STAGE_NAMES.length; k++) {
                var im = imageIn(boards[i], STAGE_NAMES[k]);
                if (im !== null) {
                    imgs[STAGE_NAMES[k]] = im;
                    used[STAGE_NAMES[k]] = true;
                    any = true;
                }
            }
            if (any) {
                rows.push({name: String(boards[i].getName()), imgs: imgs});
            }
        }
        if (rows.length === 0) {
            return null;
        }
        var cols = STAGE_NAMES.filter(function (n) { return used[n]; });
        var heights = [], total = 50;
        for (var r = 0; r < rows.length; r++) {
            var hmax = 100;
            for (var c = 0; c < cols.length; c++) {
                var im2 = rows[r].imgs[cols[c]];
                if (im2) {
                    hmax = Math.max(hmax, Math.round(cellW * im2.getHeight() / im2.getWidth()));
                }
            }
            heights.push(hmax);
            total += 40 + hmax + 10;
        }
        var W = 10 + cols.length * (cellW + 10);
        var cv = newCanvas(W, total);
        var g = cv.g;
        g.setColor(Color.WHITE);
        g.setFont(new Font('SansSerif', Font.BOLD, 28));
        for (var c2 = 0; c2 < cols.length; c2++) {
            g.drawString(cols[c2].toUpperCase(), 10 + c2 * (cellW + 10), 34);
        }
        var y = 50;
        for (var r2 = 0; r2 < rows.length; r2++) {
            g.setColor(Color.WHITE);
            g.setFont(new Font('SansSerif', Font.BOLD, 22));
            g.drawString(rows[r2].name, 10, y + 26);
            for (var c3 = 0; c3 < cols.length; c3++) {
                var im3 = rows[r2].imgs[cols[c3]];
                if (im3) {
                    fit(g, im3, 10 + c3 * (cellW + 10), y + 40, cellW, heights[r2]);
                }
            }
            y += 40 + heights[r2] + 10;
        }
        g.dispose();
        var file = new File(batch, 'overview.jpg');
        writeImage(cv.img, file, 'jpg', 0.88);
        log('overview: ' + file.getAbsolutePath());
        return file;
    }

    /**
     * placements_sheet.jpg in a board folder: every close-up in one picture, one card per designator with its
     * before / during / after thumbnails side by side. Returns the file or null.
     */
    function placementsSheet(boardDir, o) {
        var pdir = new File(boardDir, 'placements');
        var names = pdir.list();
        if (names === null) {
            return null;
        }
        var cellW = (o && o.thumbWidth) || 240, cellH = (o && o.thumbHeight) || 180;
        var perRow = (o && o.sheetColumns) || 2;
        var ids = {}, used = {};
        for (var i = 0; i < names.length; i++) {
            var m = /^(.+)_(before|during|after)\.(jpg|png)$/.exec(String(names[i]));
            if (m) {
                ids[m[1]] = true;
                used[m[2]] = true;
            }
        }
        var list = Object.keys(ids).sort();
        var cols = STAGE_NAMES.filter(function (n) { return used[n]; });
        if (list.length === 0) {
            return null;
        }
        var label = 90, cardW = label + cols.length * cellW + 10, cardH = cellH + 10;
        var W = 10 + perRow * cardW, rowsN = Math.ceil(list.length / perRow);
        var cv = newCanvas(W, 10 + rowsN * cardH);
        var g = cv.g;
        for (var k = 0; k < list.length; k++) {
            var cx = 10 + (k % perRow) * cardW, cy = 10 + Math.floor(k / perRow) * cardH;
            g.setColor(Color.WHITE);
            g.setFont(new Font('SansSerif', Font.BOLD, 20));
            g.drawString(list[k], cx, cy + 24);
            for (var c = 0; c < cols.length; c++) {
                var im = imageIn(pdir, list[k] + '_' + cols[c]);
                if (im !== null) {
                    fit(g, im, cx + label + c * cellW, cy, cellW - 4, cellH - 4);
                }
            }
        }
        g.dispose();
        var file = new File(boardDir, 'placements_sheet.jpg');
        writeImage(cv.img, file, 'jpg', 0.88);
        log('close-up sheet: ' + file.getAbsolutePath());
        return file;
    }

    /** Both montages for a whole batch folder. */
    function montage(batch, o) {
        try {
            overview(batch, o);
            var boards = subDirs(batch);
            for (var i = 0; i < boards.length; i++) {
                placementsSheet(boards[i], o);
            }
        } catch (e) {
            log('montage failed: ' + e);
        }
    }

    /**
     * The batch folder for this run:  board_snapshots/<yyyy-MM-dd>/Batch-<n>/ . A new batch (the next number of that
     * day) is started when newBatch is true or when there is none yet; otherwise the current batch is joined, so
     * the before / during / after pictures of the same boards end up together. The current batch is remembered in
     * board_snapshots/current_batch.txt.
     */
    function batchDir(newBatch) {
        var root = rootDir();
        root.mkdirs();
        var state = new File(root, 'current_batch.txt');
        if (!newBatch && state.exists()) {
            var path = String(new java.lang.String(java.nio.file.Files.readAllBytes(state.toPath()),
                java.nio.charset.StandardCharsets.UTF_8)).replace(/\s+$/, '');
            var d = new File(path);
            if (d.isDirectory()) {
                return d;
            }
        }
        var day = new File(root, new java.text.SimpleDateFormat('yyyy-MM-dd').format(new java.util.Date()));
        day.mkdirs();
        var max = 0;
        var names = day.list();
        for (var i = 0; names !== null && i < names.length; i++) {
            var m = /^Batch-(\d+)$/.exec(String(names[i]));
            if (m) {
                max = Math.max(max, parseInt(m[1], 10));
            }
        }
        var dir = new File(day, 'Batch-' + (max + 1));
        dir.mkdirs();
        java.nio.file.Files.write(state.toPath(), String(dir.getAbsolutePath()).getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }

    /** Folder name of a board: its job ID if that is short, otherwise brd<n>. */
    function boardFolder(bl, i) {
        var id = '';
        try {
            id = String(bl.getId());
        } catch (e) {
            id = '';
        }
        return (id && id !== 'null' && id.length <= 24) ? safe(id) : 'brd' + (i + 1);
    }

    /**
     * Pictures of the boards of the job for one stage.
     * cfg: {stage: 'before' | 'during' | 'after', newBatch: true | false, mode: 'board' | 'components' | 'both',
     *       onlySelected: false, board: {...}, components: {...}}
     * Layout:  board_snapshots/<date>/Batch-<n>/<board id>/<stage>.png          (one panorama per stage)
     *                                                       placements/<designator>_<stage>.png  (close-ups)
     */
    function runAll(theJob, cfg) {
        var stage = safe(cfg.stage || 'snapshot');
        var batch = batchDir(cfg.newBatch === true);
        var boards = cfg.onlySelected ? [selectedOrFirst()] : boardsOf(theJob);
        log(stage + ': ' + boards.length + ' board(s), ' + cfg.mode + ', into ' + batch.getAbsolutePath());
        for (var i = 0; i < boards.length; i++) {
            var bl = boards[i];
            var dir = new File(batch, boardFolder(bl, i));
            try {
                if (cfg.mode === 'board' || cfg.mode === 'both') {
                    board(bl, cfg.board, dir, stage);
                }
                if (cfg.mode === 'components' || cfg.mode === 'both') {
                    components(bl, cfg.components, new File(dir, 'placements'), stage);
                }
            } catch (e) {
                // One board failing must not stop the others (or the end of a job).
                log(boardFolder(bl, i) + ' (' + holderName(bl) + ') failed: ' + e);
            }
        }
        if (cfg.montage !== false) {
            montage(batch, cfg.montageOptions || {});
        }
        log(stage + ' done: ' + batch.getAbsolutePath());
        return batch;
    }

    return {
        runAll: runAll, montage: montage, batchDir: batchDir, log: log, stamp: stamp, safe: safe, rootDir: rootDir, selectedOrFirst: selectedOrFirst, boardsOf: boardsOf,
        holderName: holderName, board: board, components: components,
        File: File
    };
})();

/**
 * Orion board snapshot: drives the head camera over the board selected in the Job tab (the first one if none is
 * selected, or a rectangle you give below), takes overlapping pictures and stitches them into one image of the whole
 * board, for diagnostics (placement accuracy, what is where, fiducials, solder paste ...).
 *
 * Install: copy this file AND Orion_Snapshot_Lib.js into  %USERPROFILE%\.openpnp2\scripts\  and run it from the
 * Scripts menu (Scripts > Refresh).
 * Output: %USERPROFILE%\.openpnp2\board_snapshots\<board>_<date-time>.png  (path is shown in the log).
 *
 * The stitching does not blend: each picture contributes only its usable centre, so the result is only as good as
 * the measured scale. A seam that jumps = calibration or backlash problem, which is the point.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var MARGIN_MM = 2.0;        // extra border around the board
var EXTENT_MARGIN_MM = 4.0; // extra border around the placements when the board has no dimensions
var OVERLAP = 0.15;         // fraction of the picture that is neighbour overlap (the overlap edge is cut away)
var SCALE = 0.5;            // output scale: 1.0 = full camera resolution (can be very large), 0.5 = half
var MANUAL = null;          // or {x0: 10, y0: 10, x1: 110, y1: 90} in machine mm instead of the job's board
var CAMERA_Z = null;        // null = the camera's default Z; or a number (mm)
var MEASURE_SCALE = true;   // measure the real mm per pixel from the pictures themselves (recommended)
var SUBJECT_Z = null;       // Z of the surface that is photographed (mm): null = the board location's Z
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Orion_Snapshot_Lib.js');
load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

task(function () {
    var opts = {marginMm: MARGIN_MM, extentMarginMm: EXTENT_MARGIN_MM, overlap: OVERLAP, scale: SCALE,
        manual: MANUAL, cameraZ: CAMERA_Z, subjectZ: SUBJECT_Z, measureScale: MEASURE_SCALE};
    var bl = MANUAL ? null : OrionSnap.selectedOrFirst();
    OrionSnap.board(bl, opts, OrionSnap.rootDir());
});

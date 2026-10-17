/**
 * Runs when a job starts (off by default) (OpenPnP event "Job.Starting"): snapshots, stage before, of every board of the job (see Orion/Snapshot_Before.js).
 * Install into %USERPROFILE%\.openpnp2\scripts\Events\ (the Orion\ folder with the library goes into
 * ...\scripts\ as well). Delete the file or set ENABLED = false to stop it.
 *
 * It runs inside the job's own machine task, so the job waits for it; a failure here is only logged.
 */

var ENABLED = false;
// ---- settings -------------------------------------------------------------------------------------------------
var NEW_BATCH = true;   // true: start a new Batch-<n> folder (today's next number); false: add to the current batch
var MODE = 'both';        // 'board' (panorama), 'components' (close-ups of each placement) or 'both'
var ONLY_SELECTED = false; // true: only the board selected in the Job tab; false: every board of the job

// panorama
var MARGIN_MM = 2.0;        // extra border around the board
var EXTENT_MARGIN_MM = 4.0; // extra border around the placements when the board has no dimensions
var OVERLAP = 0.15;         // fraction of each picture that is neighbour overlap
var SCALE = 0.5;            // output scale: 1.0 = full camera resolution (very large), 0.5 = half
var MEASURE_SCALE = true;   // measure the real mm per pixel from the pictures (recommended)
var SUBJECT_Z = null;       // Z of the photographed surface (mm); null = the board location's Z
// close-ups
var INCLUDE_FIDUCIALS = false;
var ONLY_ENABLED = true;
var ONLY_BOARD_SIDE = true;
var CROSSHAIR = false;
// both
var CAMERA_Z = null;        // null = the camera's default Z; or a number (mm)
// ----------------------------------------------------------------------------------------------------------------

if (ENABLED) {
    try {
        load(scripting.getScriptsDirectory().toString() + '/Orion/Orion_Snapshot_Lib.js');
        var cfg = {
            stage: 'before', newBatch: NEW_BATCH, mode: MODE, onlySelected: ONLY_SELECTED,
            board: {marginMm: MARGIN_MM, extentMarginMm: EXTENT_MARGIN_MM, overlap: OVERLAP, scale: SCALE, manual: null,
                cameraZ: CAMERA_Z, subjectZ: SUBJECT_Z, measureScale: MEASURE_SCALE},
            components: {includeFiducials: INCLUDE_FIDUCIALS, onlyEnabled: ONLY_ENABLED, onlyBoardSide: ONLY_BOARD_SIDE,
                cameraZ: CAMERA_Z, crosshair: CROSSHAIR}
        };
        OrionSnap.runAll(job, cfg);
    } catch (e) {
        Packages.org.pmw.tinylog.Logger.warn('Snapshots (before) failed: ' + e);
    }
}

/**
 * Orion snapshots of ALL boards of the open job, stage AFTER: AFTER reflow: the soldered boards.
 * Same as Orion_Snapshots_All_Boards.js with the folder named Batch-<date-time>-after so the three stages can be told apart
 * and run independently. One folder per run, one sub-folder per board:
 *   %USERPROFILE%\.openpnp2\board_snapshots\Batch-<date-time>\brd1\, brd2\ ... (board picture + components\ folder)
 *
 * Install: copy this file AND Orion_Snapshot_Lib.js into  %USERPROFILE%\.openpnp2\scripts\  and run it from the
 * Scripts menu. To run it automatically when a job finishes, also copy Events\Job.Finished.Orion_Snapshots.js into
 * %USERPROFILE%\.openpnp2\scripts\Events\ (it has the same settings).
 */

// ---- settings -------------------------------------------------------------------------------------------------
var STAGE = 'after';            // name added to the folder: Batch-<date-time>-<STAGE> ('' = none)
var MODE = 'board';   // 'board' (stitched picture), 'components' (one picture per placement) or 'both'

// board picture
var MARGIN_MM = 2.0;
var EXTENT_MARGIN_MM = 4.0;
var OVERLAP = 0.15;
var SCALE = 0.5;
var MEASURE_SCALE = true;
var SUBJECT_Z = null;
// per component
var INCLUDE_FIDUCIALS = false;
var ONLY_ENABLED = true;
var ONLY_BOARD_SIDE = true;
var CROSSHAIR = false;
// both
var CAMERA_Z = null;
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Orion_Snapshot_Lib.js');
load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

task(function () {
    OrionSnap.runAll(gui.getJobTab().getJob(), {
        stage: STAGE,
        mode: MODE,
        board: {marginMm: MARGIN_MM, extentMarginMm: EXTENT_MARGIN_MM, overlap: OVERLAP, scale: SCALE, manual: null,
            cameraZ: CAMERA_Z, subjectZ: SUBJECT_Z, measureScale: MEASURE_SCALE},
        components: {includeFiducials: INCLUDE_FIDUCIALS, onlyEnabled: ONLY_ENABLED, onlyBoardSide: ONLY_BOARD_SIDE,
            cameraZ: CAMERA_Z, crosshair: CROSSHAIR}
    });
});

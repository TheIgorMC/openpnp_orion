/**
 * Runs when a job finishes (OpenPnP event "Job.Finished"): takes snapshots of every board of the job, see
 * Orion_Snapshots_All_Boards.js. Install into  %USERPROFILE%\.openpnp2\scripts\Events\  together with
 * Orion_Snapshot_Lib.js in  %USERPROFILE%\.openpnp2\scripts\ . Delete the file (or set ENABLED = false) to stop it.
 *
 * It runs inside the job's own machine task, so the job's end waits for it; a failure here is only logged.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var ENABLED = true;
var STAGE = 'during';      // folder Batch-<date-time>-<STAGE>
var MODE = 'both';         // 'board', 'components' or 'both'

var MARGIN_MM = 2.0;
var EXTENT_MARGIN_MM = 4.0;
var OVERLAP = 0.15;
var SCALE = 0.5;
var MEASURE_SCALE = true;
var SUBJECT_Z = null;
var INCLUDE_FIDUCIALS = false;
var ONLY_ENABLED = true;
var ONLY_BOARD_SIDE = true;
var CROSSHAIR = false;
var CAMERA_Z = null;
// ----------------------------------------------------------------------------------------------------------------

if (ENABLED) {
    try {
        load(scripting.getScriptsDirectory().toString() + '/Orion_Snapshot_Lib.js');
        OrionSnap.runAll(job, {
            stage: STAGE,
            mode: MODE,
            board: {marginMm: MARGIN_MM, extentMarginMm: EXTENT_MARGIN_MM, overlap: OVERLAP, scale: SCALE,
                manual: null, cameraZ: CAMERA_Z, subjectZ: SUBJECT_Z, measureScale: MEASURE_SCALE},
            components: {includeFiducials: INCLUDE_FIDUCIALS, onlyEnabled: ONLY_ENABLED,
                onlyBoardSide: ONLY_BOARD_SIDE, cameraZ: CAMERA_Z, crosshair: CROSSHAIR}
        });
    } catch (e) {
        Packages.org.pmw.tinylog.Logger.warn('Snapshots at the end of the job failed: ' + e);
    }
}

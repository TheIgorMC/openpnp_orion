/**
 * Orion snapshots, stage DURING: DURING / right after placing: the placed, not yet soldered components.
 *
 * Output (under %USERPROFILE%\.openpnp2\board_snapshots\):
 *   <date>\Batch-<n>\<board id>\during.png              the panorama of the board
 *   <date>\Batch-<n>\<board id>\placements\<designator>_during.png   close-ups of every placement
 * The three stages (Snapshot_Before / _During / _After) share one batch: "before" starts a new Batch-<n>, the others
 * add to the current one (remembered in board_snapshots\current_batch.txt), so the stages of a board sit together.
 *
 * Install: copy the openpnp-scripts folder CONTENTS into %USERPROFILE%\.openpnp2\scripts\ (so this file ends up in
 * ...\scripts\Orion\), then Scripts > Refresh Scripts. Run it from the Scripts > Orion menu.
 */

// ---- settings -------------------------------------------------------------------------------------------------
var NEW_BATCH = false;   // true: start a new Batch-<n> folder (today's next number); false: add to the current batch
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

load(scripting.getScriptsDirectory().toString() + '/Orion/Orion_Snapshot_Lib.js');
load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

task(function () {
    var cfg = {
        stage: 'during', newBatch: NEW_BATCH, mode: MODE, onlySelected: ONLY_SELECTED,
        board: {marginMm: MARGIN_MM, extentMarginMm: EXTENT_MARGIN_MM, overlap: OVERLAP, scale: SCALE, manual: null,
            cameraZ: CAMERA_Z, subjectZ: SUBJECT_Z, measureScale: MEASURE_SCALE},
        components: {includeFiducials: INCLUDE_FIDUCIALS, onlyEnabled: ONLY_ENABLED, onlyBoardSide: ONLY_BOARD_SIDE,
            cameraZ: CAMERA_Z, crosshair: CROSSHAIR}
    };
    OrionSnap.runAll(gui.getJobTab().getJob(), cfg);
});

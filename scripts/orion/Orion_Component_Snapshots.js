/**
 * Orion component snapshots: for the board selected in the Job tab (the first one if none is selected), moves the head
 * camera over every placement in turn and saves one picture of each. Use it to check where parts really landed.
 *
 * Install: copy this file AND Orion_Snapshot_Lib.js into  %USERPROFILE%\.openpnp2\scripts\  and run it from the
 * Scripts menu (Scripts > Refresh).
 * Output: a new folder  %USERPROFILE%\.openpnp2\board_snapshots\<board>_<date-time>\  with  001_R1.png, 002_C3.png ...
 * and an index.csv (number, designator, part, machine X, machine Y).
 */

// ---- settings -------------------------------------------------------------------------------------------------
var INCLUDE_FIDUCIALS = false;   // also take pictures of the fiducial placements
var ONLY_ENABLED = true;        // skip disabled placements
var ONLY_BOARD_SIDE = true;     // only placements on the side of the board that is up
var CAMERA_Z = null;            // null = the camera's default Z; or a number (mm)
var CROSSHAIR = false;           // draw a cross at the placement position and the designator
// ----------------------------------------------------------------------------------------------------------------

load(scripting.getScriptsDirectory().toString() + '/Orion_Snapshot_Lib.js');
load(scripting.getScriptsDirectory().toString() + '/Examples/JavaScript/Utility.js');

task(function () {
    var opts = {includeFiducials: INCLUDE_FIDUCIALS, onlyEnabled: ONLY_ENABLED, onlyBoardSide: ONLY_BOARD_SIDE,
        cameraZ: CAMERA_Z, crosshair: CROSSHAIR};
    var bl = OrionSnap.selectedOrFirst();
    var dir = new OrionSnap.File(OrionSnap.rootDir(), OrionSnap.holderName(bl) + '_' + OrionSnap.stamp());
    var done = OrionSnap.components(bl, opts, dir);
    OrionSnap.log('done: ' + done.getAbsolutePath());
});

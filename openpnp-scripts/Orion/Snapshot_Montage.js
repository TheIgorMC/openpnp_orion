/**
 * Rebuilds the montages of the CURRENT batch (the one the next stage joins, see board_snapshots\\current_batch.txt):
 *   overview.jpg              all panoramas: one row per board, one column per stage (before / during / after)
 *   <board>\\placements_sheet.jpg   every close-up of the board in one picture, before / during / after side by side
 * They are also built automatically at the end of every Snapshot_Before / _During / _After run (MONTAGE = true).
 * Does not move the machine.
 */

load(scripting.getScriptsDirectory().toString() + '/Orion/Orion_Snapshot_Lib.js');

var batch = OrionSnap.batchDir(false);
OrionSnap.log('montage of ' + batch.getAbsolutePath());
OrionSnap.montage(batch, {});

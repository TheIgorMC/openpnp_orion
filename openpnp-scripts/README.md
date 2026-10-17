# OpenPnP scripts for Orion

Copy the CONTENTS of this folder into `%USERPROFILE%\.openpnp2\scripts\`, so you get:

```
scripts\
  Orion\
    Orion_Snapshot_Lib.js     (shared code, does nothing alone)
    Snapshot_Before.js        run from  Scripts > Orion  (after Scripts > Refresh Scripts)
    Snapshot_During.js
    Snapshot_After.js
    Snapshot_Montage.js       rebuilds the montages of the current batch (does not move the machine)
  Events\
    Job.Finished.Orion_Snapshots.js   runs the "during" stage when a job finishes (ENABLED = true)
    Job.Starting.Orion_Snapshots.js   runs the "before" stage when a job starts   (ENABLED = false)
```

## Snapshots

Three stages of a board: **before** (paste printed), **during** (placed, not soldered), **after** (soldered). Each is run
on its own, on every board of the open job (or only the selected one, `ONLY_SELECTED`). Output:

```
%USERPROFILE%\.openpnp2\board_snapshots\
  2026-10-20\
    Batch-1\
      <board id>\
        before.png  during.png  after.png          panoramas (stitched, scale measured from the pictures)
        placements\
          R1_before.jpg  R1_during.jpg  R1_after.jpg ...   close-ups of every placement, cropped
          index_before.csv  index_during.csv ...
      overview.jpg                  all panoramas of the batch: a row per board, a column per stage
      <board id>\placements_sheet.jpg  every close-up of a board in one picture (before | during | after per part)
  current_batch.txt                                  the batch the next stages join
```

"Before" starts a new batch (the next `Batch-<n>` of the day); "during" and "after" join the current one, so the three
stages of a board end up side by side. Set `NEW_BATCH` at the top of a script to change that. The folder of a board is its
job ID, or `brd1`, `brd2` ... if the ID is long.

Defaults: **before** = panorama and close-ups of a random sample of 6 placements per board (`SAMPLE_COUNT`, quick);
**during** and **after** = panorama and all close-ups. Close-ups are cropped to the
part's footprint (body and pads of its package) plus `CROP_MARGIN_MM` (2 mm) on every side, saved as JPEG
(`CLOSEUP_FORMAT`, `JPG_QUALITY`); a part whose package has no footprint gets a `FALLBACK_MM` square. `crop_mm` in
`index_<stage>.csv` tells the size of each crop. The picture scale for the crop is measured from the pictures
themselves, once per run.

The settings (panorama scale, overlap, which placements, camera Z ...) are at the top of each script. The scripts are
JavaScript (Nashorn) and have not been run on every setup: if one fails, the log has the line.

## Montages

At the end of every stage run (`MONTAGE = true`) two summary pictures are rebuilt from what the batch holds so far:
`Batch-<n>\overview.jpg` (all board panoramas, rows = boards, columns = before / during / after) and, in each board
folder, `placements_sheet.jpg` (one card per designator with its before / during / after close-ups side by side).
`Snapshot_Montage.js` rebuilds them on demand without moving the machine.

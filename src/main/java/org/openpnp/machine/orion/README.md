# Orion feeder support

OpenPnP driver for the OrionPnP RS485 tape feeders (protocol: `Feeders/Feeder board/SW/PROTOCOL.md`
in the OrionPnP repository). Everything lives in `org.openpnp.machine.orion`; the only change to
existing OpenPnP code is one line registering the class in `ReferenceMachine`.

## What you get

Add a feeder of type **OrionFeeder**. Every Orion feeder shows three tabs:

* **Orion Feeder** - per feeder. Bind to a physical unit (by its factory serial, not its bus
  address), edit part / pitch / peel coupling / peel time / LED brightness / pick location. Apply
  pushes the values to the unit immediately. Live buttons: Identify, Feed, Unfeed, Peel, Unpeel,
  Stop, Jog, Zero here, Read from unit. Vision section: fiducial part, location and mode
  (see below).
* **Orion Bus Manager** - machine wide. Interface choice and settings, Connect / Disconnect, one
  sub-tab per rail with every unit sorted by X position of its feeder: address, serial, state,
  component, tape width, bound feeder, X, note. Scan rail, Ping, Identify, Feed/Unfeed/Peel/Unpeel
  on any unit (bound or not), Create feeder from an unbound unit, Forget, rail power (host board).
* **Orion Debug** - live TX/RX/info/error log with filters (also written to the OpenPnP log at
  trace/warn level), raw command sender, probes (status, I2C scan, component, peel settings,
  serial re-verify).

With the *Connect and scan automatically when the machine is enabled* option (default on) the
bus connects and scans when the machine is enabled. Otherwise it connects on first use.

## One object per feeder

Like every OpenPnP feeder, each physical Orion feeder is its own `OrionFeeder` object in the machine's
feeder list, bound to its unit by serial number (own part, pick location, pitch, peel settings).
The orchestrator is `OrionManager` (one per program): it owns the connection, the rails and the
unit table, and creates the feeder objects. Entry points that need no existing feeder:

* Main menu **Orion**: *Bus manager and debug...* (window with the Bus Manager and Debug tabs),
  *Connect, scan and create all feeders* (one step), *Verify all rails by vision*.
* Automatically: when the machine is enabled it connects, scans and (option, default on) creates a
  feeder for every unit that has none.
* Bus Manager rail tab: *Create feeders for all unbound* (that rail) or *Create feeder* (selected unit).

New feeders are named `Orion R1-xxxx`, bound by serial, ordered by slot X, never duplicating a bound
unit. Assign a part to each in the Feeders tab. The per-feeder tabs (Orion Feeder, Bus Manager, Debug)
remain on every feeder as well.

## Interfaces

| Mode | Hardware | Rails |
|---|---|---|
| USB-RS485 adapter | any adapter that does its own direction control, 9600 8N1 | 1 |
| Orion host board | USB CDC, two isolated RS485 rails, line protocol below | 2 |
| Simulated | none; 6 virtual feeders on 2 rails | 2 |

The transport is native Java (jSerialComm), not a script actuator, so scans and conflict
handling do not depend on G-code driver regexes and do not stall the motion controller.

### Host board line protocol (115200, `\n`-terminated)

```
VER                                   -> OK orion-host <version> rails=<n>
RS485 <rail> <hexframe> <timeoutMs> <collectMs>
                                      -> RX <hexframe>      (0..n lines)
                                         OK                 (or ERR <text>)
RAIL <rail> ON|OFF                    -> OK | ERR <text>
RAIL <rail> ?                         -> OK ON|OFF [mA=<n>] [FAULT]
```
Rails are 0-based in the protocol, shown as Rail 1/2 in the UI. `collectMs > 0` keeps listening after
the first reply (needed for DISCOVER, where several feeders answer with random jitter). No reply is
`OK` with no `RX` lines.

## Settings: unit values win unless you set them

Pitch, peel time, peel rate and LED brightness are only pushed to a unit when you set them in the
feeder tab (pitch 0, peel -1 and LED 0 mean *leave what the unit has*). Feeders created from a unit,
and *Bind to unit*, read the unit's own values (e.g. set with the Python GUI) and show them under
"Unit reports"; *Read from unit* does the same on demand and pushes nothing. A feeder created by an
earlier build may still hold a pitch of 4: press *Read from unit* once (or delete and recreate it).

## Part <-> component id on the unit

If your OpenPnP part ids are plain numbers (e.g. `1234`, also zero padded like `0042`) the unit's
component id IS the part id: no table, reading a unit assigns that part, assigning a part writes its
number. For other part ids, Orion keeps a table part id <-> component id (saved with the
machine). When a unit is read (creating its feeder, *Bind to unit*, *Read from unit*, or first contact of
a feeder without part) and its id is paired with a part, the feeder gets that part and is enabled, no
checking needed. The first time you assign a part to a feeder whose unit already holds an unpaired id
(e.g. set with the Python GUI) the pairing is learned and nothing is written to the unit. A part with
no id yet gets a free number (a numeric part id keeps its number) and that is written to the unit;
changing a unit's id makes it clear tape zero and pitch (firmware behaviour), so the pitch / peel
settings are pushed again right away. Unit refusals now name the cause ("no peel time saved on the
unit", "no pitch saved on the unit").

## Identity, scanning and conflicts

* A feeder is bound by **serial** (16 byte factory serial, from `CMD_GET_SERIAL`). Bus addresses
  are disposable: a unit that reboots or moves rail is found again and the OpenPnP feeder follows it.
* **Scan** = probe every address 1..N with PING (finds units that kept their address while the PC
  restarted) -> identify them -> broadcast DISCOVER and assign free addresses to new units. Units
  that no longer answer become LOST.
* States: ONLINE, LOST, NO_SERIAL (answers but has no readable serial: it cannot be bound reliably),
  CONFLICT (a reply came from an unexpected address, or the serial at an address changed).
* Two units answering the same address garble each other, so the frame is dropped and the command
  times out; the Bus Manager and the debug log show it, a rescan reassigns.
* The same serial on two rails is reported as a conflict and refuses to feed.
* A feed that times out triggers a rescan and retry (`Feed retries`). A NOT_READY NACK re-pushes
  the pitch and retries. Stall / fault / magnet-lost / timeout NACKs are shown with their meaning.

## Slot position X and address restore (firmware v0.02b)

Each unit stores a slot position X (0.1 mm units, `CMD_SET_POSITION` / `CMD_GET_POSITION`, announced
in discovery together with its last address). The Bus Manager reads it on every scan and sorts each
rail by it, so the table matches the physical layout, also for unbound units. It is a cheap layout
check, not an identity: binding stays on the serial.

* Feeder tab: Read / Write the position on the unit; "Teach" remembers the position the pick
  location was taught at; with "Shift pick X when the unit's slot X changes" a feeder moved to
  another slot gets its pick X shifted by the difference. A moved unit is flagged in the state line.
* After a power cycle units announce their last address; a scan gives each its old address back when
  that is unambiguous (no two units claim it, and it is free), otherwise it assigns a fresh one.

## Rail verify and rescan by vision (large fiducial)

Per rail you give the X range, the Y/Z of the large (middle) fiducials, step (6 mm), exclusion
(5 mm) and the shift limits; the large fiducial part is chosen once in the connection panel
(its vision settings are the detection). **Verify rail (vision)**:

1. Refresh the bus table (who answers, slot positions).
2. Check every feeder of the rail, one after the other, at its last known fiducial X. A shift between
   "save shifts >" and "reject >" is saved: the pick X and the fiducial move by the same amount.
   Nothing is saved for shifts below the noise limit.
3. Only after ALL feeders are checked, if any is missing, ONE rescan: sweep the free stretches of the
   rail (outside +-exclusion of the feeders that were found) in steps, collecting large fiducials.
4. Association is automatic only when unambiguous: the unit's slot position moved (the fiducial must
   be near the shifted X), or exactly one missing feeder and one unclaimed fiducial remain. Otherwise
   the feeder is reported UNRESOLVED and nothing is guessed: jog the camera over its fiducial and use
   **Locate selected here** (manual). Missing feeders whose unit does not answer stay missing.
   Fiducials nobody claims are reported (new, unbound feeders).

With "Rescan automatically" off, step 3 is skipped and the result just says a rescan is needed.
Verify moves the camera, so it only runs on a homed machine, as a machine task; it is a button,
not run automatically before a job.

### Warning when no verify was run

At job preparation every bound Orion feeder checks whether its rail was verified by vision since
the program started, with a clean result, and with the same units on the bus as at that time
(a new, missing or moved unit invalidates it). If not: a warning dialog (once a minute per rail),
an entry in the Orion log, an OpenPnP Issues & Solutions entry, and a red "NOT VERIFIED" line on the
rail tab. Setting (connection tab): *Don't check* / *Warn (job still runs)* / *Block the job*.

### Which unit is this fiducial? Fiber light, tape movement fallback

When fiducials are left over that cannot be matched directly, the camera goes to each one (plus the
per-rail fiber spot offset) and a **binary search** decides which pending unit it is: half of the
candidates light their fiber (`CMD_SET_EXT_LED`), the camera sees whether the spot lit up, the
answer halves the candidate set. First all candidates are lit together to prove the light is
visible at all, and the last one is confirmed alone, so a dim LED or a wrong spot returns "could not
decide" instead of a guess. 8 candidates take 5 lit/unlit comparisons.

Method (Feeder identification tab): *Off*, *Fiber light*, *Tape movement*, or *Fiber light, tape
movement as fallback*. The fallback goes to the sprocket-hole offset, takes a picture of the masked
hole, moves the candidates' tapes back (default 0.5 mm, `CMD_JOG`), takes another and counts changed
pixels, then moves forward again; same binary search, slower because tapes really move. Moves are staggered: one unit at a time, at
least *stagger* ms (default 150) between any two move commands, also when moving forward again, so
feeder motors never start together and load the 12 V rail. If a move fails halfway, every tape that
was moved back is still sent forward again.

Tuning (same tab): two normal OpenPnP pipelines, *fiber* and *tape movement*, edited with the
pipeline editor. Both end in a gray image of a masked spot (circle mask stage `FiberSpot` /
`TapeHole`, blur, gray). Fiber brightness = peak of that image; movement = pixels changed by more than
25 levels. Procedure: select the unit in the rail table, jog the camera over the fiber spot, **Fiber
ON**, edit the pipeline until the spot stands out, **Test fiber** shows off / on / rise and the verdict
against the threshold (**Fiber OFF** to switch it off). **Test tape movement** does the same with
the jog. A brighter LED just raises the rise; adjust the threshold.

The fiber spot and sprocket hole offsets (from the large fiducial, per rail) can be typed in or taught:
select a feeder whose large fiducial is known, jog the camera over the fiber spot (or a hole) and press
**Teach here**. The settle time (camera wait after switching a fiber or moving a tape) is one shared
field on the identification tab.

## Vision (fine X position)

A fiducial part on or beside the feeder marks its real position. Set the fiducial part (its
vision settings / pipeline is the "specialised fiducial detection"), teach the fiducial location
with the camera, then choose when to measure: never, on the first feed of each job, or before every
feed. The measured shift (X only by default) is added to the pick location; shifts over the limit
are rejected. "Locate fiducial now" measures once, "Make correction permanent" bakes it in.

## Status

* Protocol, transports, bus manager, feeder logic and persistence are covered by unit tests
  (`OrionBusTest`, `SerialTransportsTest`, `OrionFeederTest`) running against a simulated bus.
* The GUI was only checked headlessly (screenshots against the simulator).
* NOT yet tested: real feeders, the real host board (no firmware exists yet), the vision path on a
  real machine.

## Build and run (Windows)

```
mvn -DskipTests package
openpnp.bat
```
Needs a JDK (the project targets Java 11) and Maven.

## Troubleshooting

* **Serial library**: the Orion code copies the matching native serial library from the jar to
  `%USERPROFILE%\.openpnp2\orion-native\...` and sets `jSerialComm.library.path` to it before first
  use, so jSerialComm's own unpacking (which tries an ARM DLL first on Windows and can fail on a
  locked or stale file) is bypassed. If you still see the red "Serial library" message, it prints the
  real error text, `os.arch`, the Java version and the path in use: send me that line.

* **"Cannot load native library ... jSerialComm.dll (Access denied / Can't load ARM 64-bit .dll)"**:
  jSerialComm unpacks its native DLL into `%TEMP%\jSerialComm\<version>` and `%USERPROFILE%\.jSerialComm`
  on first use. A stale, locked or wrong-architecture file there stops it from loading. Close every
  OpenPnP / Java process (also hidden ones in Task Manager), delete those two folders, start again.
  The Bus Manager still opens without the library and shows this hint; the simulated interface
  never needs it. If it persists, check `java -XshowSettings:properties -version` for `os.arch`
  (it must match your Windows, normally `amd64`).
* The `Unsafe`, `CoInitializeEx` and "restricted method" warnings at start-up come from OpenPnP's
  own libraries and are harmless.

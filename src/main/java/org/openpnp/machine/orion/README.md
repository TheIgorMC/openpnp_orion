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

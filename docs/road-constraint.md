# Road constraint — design plan (Phase 2)

Status (2026-10-01): **M0–M5 implemented and measured in replay** (D-041…D-044, R-020); M6 (app) built but
not yet run on a device; M7 later. Decisions taken while implementing are in `decisions.md`, results in `progress.md`.
Differences from the plan below: the matcher runs on a road-free twin estimator (D-042); updates need a
known speed and ≥ 150 m of confident matching; the reported radius stays the road-free one (D-043).

## Why

Without road knowledge the EKF cannot tell that a road is straight. With network fixes of ~30–40 m
per-axis noise every 13–15 s, each fix turns the heading by a few degrees, and the track drifts up to
40 m across the road before the next fix turns it back (R-019). Tuning cannot fix it (R-019), limiting
the heading gain made it worse (D-035), and Google errors are nearly independent while driving, so a
bias state would not help either (R-019). A parallel offset from the road (R-008 09:05:32) and a turn
taken "20 m early" are the same problem.

## What we want (user's three requirements)

1. **Heading from the road.** Driving close to a road while the gyro shows no turning means we are
   probably on that road, and the heading should match its bearing (or the opposite one).
2. **Position from the trajectory shape.** If the gyro says we turned but our estimate is 20 m before
   the intersection, we are probably already at the intersection. The same applies to a road bend
   that would fit 20 m earlier or later. But a lane change must not look like a bend.
3. **Do not overdo it.** We may be in a car park, a yard, or on a road that OSM does not have yet (drive
   A, the ramp mapped as construction, R-015b). Then the constraint must switch itself off.

## Architecture: EKF stays, a road matcher feeds it soft pseudo-measurements

```
MotionTracker (gyro, OBD odometry) ─┐
EKF pose + covariance ──────────────┼─► RoadMatcher (online HMM with an explicit OFF-ROAD state)
coarse fixes ───────────────────────┘          │
                                               ▼  only when confident and gated
                         EKF pseudo-measurements: heading, cross-track, along-track
```

- **Why not replace the EKF with a particle filter now:** the EKF already handles the sensors, trust and
  honest uncertainty, and is measured on four drives. A matcher that sends *soft, gated* measurements can
  be switched off per case (the "do not overdo" requirement) and compared in the ladder rung by rung.
  The multi-hypothesis particle filter stays planned for ambiguous starts and parallel roads (M7).
- **Determinism:** the matcher lives in `:core`, has no wall clock, and replays byte-identically. The
  road data is an input file, like a recording.

## Components

### M0 — Road data (`RoadGraph`)

- Source: OSM `highway=*` ways for the drive region (motorway … residential, service, living_street;
  not footway, cycleway, path, track, steps or proposed). Keep `oneway`, `layer`, `bridge`, `tunnel`,
  `lanes`, `highway` (class), `junction=roundabout`, `construction`.
- Build tool (offline, Python like `tools/truth/osm_match.py`, which already parses Overpass JSON):
  Overpass or Geofabrik extract → a compact file (segments split at intersections, polylines, a grid
  spatial index). Load it in `:core` with plain Kotlin (no Android). Implement the existing `RoadGraph`
  interface (`core/future/Interfaces.kt`).
- Decision to log: own format vs GraphHopper or Barefoot import (heavy, JVM-only parts, Android size).
  Leaning: own small format, since we need only geometry, topology and a few tags.
- App: download a region *before* the drive (jamming does not block mobile internet; downloading the
  map tile is not the user's trace). Show the size, ask first, store it in app files, keep it out of git.
  ODbL attribution in About.

### M1 — Online matcher (`RoadMatcher`), offline-evaluated first

An HMM over road candidates (Newson & Krumm, as in `osm_match.py`, but causal):

- **Observations:** the EKF pose every ~10 m of odometry (with its covariance, not a fixed σ), plus the
  gyro heading change since the previous step. Coarse fixes enter only through the EKF, so they are not
  counted twice.
- **Candidates:** segments within ~3σ of the pose (floor 30 m), both directions unless one-way.
- **Transitions:** road distance vs odometry distance (as in `osm_match.py`), and road bearing change
  vs gyro bearing change; connectivity respects one-way, layer and bridge (an overpass is not an
  intersection).
- **OFF-ROAD state:** always present, with a fixed prior per step (for example 1–2 %), its own
  likelihood (a broad distribution with no heading expectation), and transitions to and from any road.
  It wins when:
  - no road explains the pose within its covariance;
  - the gyro path does not fit the road geometry (several turns where the road is straight, U-turns, or
    the three-point turns of car parks);
  - the vehicle is slow (< 15 km/h) and turning a lot (parking search).
  It must lose only slowly: re-entering a road needs ~100–200 m of consistent matching (hysteresis).
- **Output per step:** P(off-road), the best road state (segment, distance along, direction), its
  probability, and the margin over the second-best (parallel roads and dual carriageways are a real
  ambiguity).
- **Decisions use a short fixed lag** (for example 30–50 m behind the head) for the along-track
  feature matching (M4); heading and cross-track use the filtered head.

M1 ships no EKF change: replay writes the matcher's state to `ticks.csv`, and we measure how often it
picks the right road, including the known off-road stretches (R-007 underground car park, the end of
drive A, parking searches at the ends of drives).

### M2 — Heading from the road (requirement 1)

A 1-D heading measurement ψ = road bearing (or +180° against the segment direction), applied only if all
of these hold:

- P(on this road) ≥ 0.9 and the margin over other roads ≥ 0.8;
- the road is locally straight: bearing change < ~5° over ±40 m around the position;
- the gyro shows no turn: |integrated yaw| < ~3° over the last ~30–50 m;
- speed > ~15 km/h (not manoeuvring);
- NIS gate; an innovation above it is evidence against the match and goes back to the matcher.

σ = OSM bearing error (~1–2°) ⊕ lane-change allowance (~3°). Apply it at most every ~30–50 m of
travel: consecutive road headings are the same evidence (P5), and applying them every tick would make
the heading overconfident.

Expected effect: removes the R-019 wobble (the heading stays at the road bearing between fixes).

### M3 — Cross-track from the road

A 1-D position measurement along the road normal: offset 0 from the centreline, with σ = half the
carriageway width (lanes × 3.5 m / 2, default 2 lanes) ⊕ OSM geometry error (~5 m). Same conditions as
M2 except the straightness one, same rate limit and gate. Along-track position is not constrained here.
It fixes the parallel offset (R-008 09:05:32) directly.

### M4 — Along-track from turns and bends (requirement 2)

Match the *shape* of the recent path to the road ahead and behind:

- Take the gyro heading profile over the last ~150–300 m of odometry, ψ_gyro(s), and the road bearing
  profile ψ_road(s) along the matched path. Find the along-track shift Δs (±50 m) that best aligns them.
  This is a 1-D cross-correlation, cheap per step.
- Feed Δs as an along-track measurement only if the window contains a **persistent** bearing change:
  net ≥ 20° that stays (an intersection turn or a real bend). σ comes from the width of the correlation
  peak, plus the odometry scale error.
- **Lane change vs bend:** a lane change is an out-and-back heading excursion (≈ 3–8°, 3–6 s, net ≈ 0,
  lateral ≈ 3.5 m). The net-change and persistence conditions ignore it, and it never shifts along-track
  position. A bend that the road has and the gyro does not show, or the other way round, counts as
  evidence against the match (to the matcher), not as a shift.
- Turn events at intersections also discriminate between candidate roads (a 90° turn kills
  straight-on hypotheses). That is the matcher's job, from the same profile.

### M5 — Robustness ("do not overdo")

- All road measurements are soft and gated. Two or three consecutive gate rejections → P(off-road) up,
  constraints paused.
- Off-road or unsure → no road measurements: the Phase 1 EKF runs unchanged (it is the fallback, not a
  degraded mode).
- Scenario steps for replay:
  - `remove_roads` (drop OSM segments in a polygon or a time window) to simulate a road missing from OSM;
  - `shift_roads` (offset geometry by N m) to simulate a bad map.
- Acceptance: on the off-road stretches the error must not be worse than without the road constraint
  (same run with the rung off), and P(off-road) must rise within ~50–100 m.
- Trust interaction: the road state may later help spoofing detection (a GNSS track that leaves every
  road while the gyro shows none of its turns). Until then, road measurements are never used to accept
  GNSS. GNSS-derived heading stays GNSS evidence (P21).

### M6 — App

Region download and storage, a settings toggle, the matcher state on the debug screen (on road / off
road / road name), recording of the matcher output in the drive bundle (schema bump if needed).

### M7 — Later

A multi-hypothesis road-state particle filter (ambiguous ±800 m starts, parallel roads), a
planned-route prior, and the road for the heading bank (align the bank's path shape to roads).

## Evaluation

- New ladder rungs `phone+network+osm` and `phone+network+obd+osm`.
- **Circularity warning:** the jammed drives' truth is itself OSM-matched (`osm_match.py`), so a
  road-constrained estimator scores unfairly well there. Primary numbers come from the GNSS drives (R-007,
  B) with GNSS truth and the drop scenarios. The jammed drives serve for the qualitative check and the
  off-road check.
- Metrics, in addition to p50, p95 and within68/95: cross-track error, heading error on straight
  roads, along-track error after turns, the fraction of time on the wrong road, and the error on
  off-road stretches vs the rung without the road.
- Synthetic: generate a road graph from `DriveSimulator` legs for unit tests (straight, turn, a
  parallel road 20 m away, a missing segment).

## Order and size

| Step | Content | Size | Gate to continue |
|---|---|---|---|
| M0 | Road file builder, `RoadGraph` + index, tests | S | loads the Kyiv extract in < 1 s on JVM |
| M1 | Online HMM matcher with OFF-ROAD, offline evaluation | M | right road ≥ 95 % on GNSS drives; off-road detected on known stretches |
| M2 | Heading pseudo-measurement | S | R-019 wobble gone; no regression in the matrix |
| M3 | Cross-track pseudo-measurement | S | cross-track p95 down; calibration kept |
| M4 | Along-track from turn and bend alignment | M | along-track error after turns down; no shifts from lane changes |
| M5 | Robustness scenarios (`remove_roads`, `shift_roads`) | S | off-road no worse than without the road |
| M6 | App: download, toggle, debug UI, recording | M | first live drive with the road constraint |
| M7 | Particle filter, route prior | L | later |

## Answered questions

1. Map region: the app downloads tiles around its own location (D-041).
2. `lanes` in Kyiv: on 95–100 % of trunk/primary/secondary/tertiary length, 45–67 % of residential, 2–3 % of
   service; class defaults fill the gaps (`RoadWidth`).
3. Car parks: aisles, driveways and drive-throughs are left out, so they count as off-road (D-041).

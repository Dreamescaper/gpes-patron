# gpes-patron: GNSS-resilient location provider (research PoC)

This is an Android proof of concept for keeping useful vehicle localization while GNSS is jammed,
spoofed, or unavailable for hours. It is designed for urban driving under persistent interference.
It publishes its estimate as Android mock location, so existing navigation apps (Google Maps, Waze)
keep working.

GNSS is treated as **untrusted evidence**. The long-term estimator is road-constrained: it tracks
*which road and where along it*, not an integrated free-space position. Phase 1 (this code)
provides the plumbing, recording, trust evaluation, a baseline estimator, mock output, and an
offline replay/fault-injection framework to measure what each extra signal is worth.

- Start here: [AGENTS.md](AGENTS.md) (also the contributor guide)
- Architecture: [docs/architecture.md](docs/architecture.md)
- Algorithm: [docs/estimation-algorithm.md](docs/estimation-algorithm.md)
- Status and results: [docs/progress.md](docs/progress.md) · Roadmap: [docs/roadmap.md](docs/roadmap.md) · Decisions: [docs/decisions.md](docs/decisions.md)

## Quick start

```bash
./gradlew :core:test :recording:test :app:assembleDebug :replay-cli:installDist

# Simulated end-to-end, no phone needed
R=replay-cli/build/install/replay/bin/replay
$R simulate --out /tmp/sim/drive.db --network-period 20
$R matrix --drive /tmp/sim/drive.db --truth /tmp/sim/drive.truth.json --scenario scenarios --out /tmp/sim/out
cat /tmp/sim/out/comparison.md

# Phone
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set gpes.patron android:mock_location allow   # or Developer options → Select mock location app
```

For OBD vehicle speed, pair an ELM327 Bluetooth adapter in Android settings (PIN usually 1234 or
0000), then enable "Use OBD vehicle speed" in the app and select it.

In the app, pick **Record only** for data collection drives, **Estimate only** to see the
estimator live, or **Mock location output** to feed other apps. Then share the `.db` and run
`replay run --drive <file>.db --scenario scenarios`.

⚠️ This is experimental research software. Mock location affects every app on the device. Do not
rely on it for safety-critical navigation.

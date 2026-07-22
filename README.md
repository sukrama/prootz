# prootz

A minimal Android terminal that boots straight into a **proot Linux** environment.
Default distro is **Alpine** (tiny, for fast iteration); Ubuntu/Debian can be added later.

## How it works

- **Terminal UI**: Termux's original `terminal-emulator` + `terminal-view` modules (unmodified),
  giving a real PTY-backed terminal.
- **proot**: shipped as `jniLibs/<abi>/libproot_exec.so` + `libproot_loader.so`. Android extracts
  these to the app's native library dir and marks them executable, so there is **no runtime
  download of a `.deb`** (this is what makes it reliable — Android has no `ar`/`tar`/`xz`).
- **Rootfs**: on first launch the Alpine rootfs tarball (`proot-distro`) is downloaded and
  extracted with a pure-Java tar reader + XZ decoder into `filesDir/rootfs/alpine`.
- On launch, a `TerminalSession` execs `libproot_exec.so` in a PTY with the proot arguments,
  dropping you into `/bin/ash -l` inside Alpine.

## Modules

| Module | Purpose |
|--------|---------|
| `app` | The application: rootfs installer, proot wiring, terminal Activity |
| `terminal-emulator` | Termux VT/ANSI engine + JNI PTY (upstream, unmodified) |
| `terminal-view` | Termux terminal `View` (upstream, unmodified) |

## Build

Pushing to `master`/`main` triggers `.github/workflows/build.yml`, which produces a debug APK
uploaded as the `prootz-debug` artifact.

Locally:

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

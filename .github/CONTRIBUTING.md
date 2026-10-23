# Contributing to Nota

Bug reports, ideas and pull requests are welcome.

## Reporting a bug

Open an issue with the bug template. If the app crashed, Nota writes a report to `Download/nota-crash-*.txt` on the phone; attaching it helps the most.

Security problems go through the [Security tab](https://github.com/mankidev/nota/security/advisories/new) instead, see [SECURITY.md](SECURITY.md).

## Building

Nota builds with a single shell script, without Gradle. On Android with [Termux](https://termux.dev):

```sh
pkg install openjdk-21 aapt2 d8 apksigner zip
cp .../platforms/android-33/android.jar tools/android33.jar
./build.sh
```

On a desktop with the Android SDK, put `build-tools/33.0.2` on your `PATH` and point `ANDROID_JAR` at `platforms/android-33/android.jar`. The APK lands in `build/nota.apk`.

## Code style

- Java 8 source, framework APIs only. No Gradle, AndroidX or outside libraries.
- No comments in the code. Clear names carry the meaning; anything that needs explaining goes in the pull request.
- English names for everything in the code.
- Match the formatting of the file you are editing.

## Pull requests

- Keep one change per pull request.
- Run `./build.sh` before opening it; the CI runs the same script.
- Say what you tested on a phone, and on which Android version.

By contributing you agree that your work is released under the [GPL-3.0-or-later](../LICENSE) licence.

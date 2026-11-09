# Contributing to PhoneWheel

Thanks for helping! PhoneWheel is a small open-source project, and bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/ihavenoenemies171/PhoneWheel/issues/new/choose) using the **Bug report** template, and include:
- the app version (in the release name) and PhoneWheel.exe version;
- your phone model and Android version;
- the game, and the connection method (WiFi, USB tethering, hotspot or adb);
- a screenshot of the PhoneWheel.exe window and what you expected to happen.

## Suggesting a feature

Use the **Feature request** template. Say what problem it solves while you drive. The guiding idea for feedback features: **only tell the driver what they can act on.** Less but clearer feedback beats more feedback.

## Sending a change

1. Fork the repo and create a branch.
2. Make your change. Keep the Android app free of third-party libraries, and the PC program to the packages in `pc/requirements.txt`.
3. Test it:
   - **PC side:** `python server.py --dry-run` runs without vJoy. Telemetry and coach logic can be tested with synthetic data; see [docs/BUILDING.md](docs/BUILDING.md).
   - **Android side:** build with `gradle assembleRelease`, or push to your fork and let GitHub Actions build the APK.
4. Update the docs in `docs/` if you changed behaviour or settings.
5. Open a pull request explaining what changed and how you tested it.

Good first contributions:
- telemetry support for more games, such as Automobilista 2, rFactor 2 standalone or Assetto Corsa;
- a configurable button layout;
- translations of the docs.

## Code style

- **Kotlin:** official Kotlin style, with small focused classes. UI drawing stays in `WheelView`.
- **Python:** standard library style, no heavy dependencies, functions that don't crash the driving loop. Catch and log errors instead.

By contributing, you agree that your contributions are licensed under the project's [MIT License](LICENSE).

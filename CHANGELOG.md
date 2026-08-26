# Changelog

## Unreleased — Radio Helper installation from Settings

- Added a Settings card that reports whether the matching Radio Helper companion is installed, installs the helper bundled inside Bada, and opens the helper setup screen after installation.
- Embedded the matching debug or release helper APK in generated app assets and applied Bada's release signing inputs to the release helper so the signature-protected radio service can bind.
- Hardened PackageInstaller confirmation, duplicate taps, session cleanup, unknown-source denial, and background confirmation handling.
- Documented the Settings visuals, installer/receiver ownership, variant and signing invariants, concurrency/lifecycle boundaries, failure fallbacks, and device test matrix adjacent to their source owners.
- Verification: static source/reference checks, XML parsing, and `git diff --check` passed. Android compilation, pull-request CI, and the real Settings/system-installer click path were not run because compilation was not authorized for this task; CI can be requested later with a follow-up commit.

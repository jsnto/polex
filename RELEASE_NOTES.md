# Release notes

The public GitHub release for a tag is published from its entry below (the
entry is the release body). The app's **Settings → Update** pane installs
the newest release's `polex-release.apk` asset.

## v1.5

**Stable release signing — one-time device cutover required.**

v1.3 and v1.4 were each signed with a *different* debug key (the release
build signed with the debug keystore, and the Docker build regenerated a
fresh one per build). Android will not install an update signed by a
different key than the installed app (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
so **devices running v1.3 or v1.4 cannot update in place to v1.5.**

**One-time cutover:** on any phone/tablet with the old polex build
installed, **uninstall polex, then install this v1.5 APK** (clean install).
Nothing is lost: the server list and its settings live on the backend, not
in the app — the app signs in and re-fetches them.

From v1.5 on, every `polex-release.apk` is signed by ONE permanent release
key (kept outside the repo, on the build host, and mounted into the
containerized build — see "Release signing" in `android/AGENTS.md`), so
future releases update in place as normal.

Also: version bump 1.4 → 1.5 (versionCode 4) so the first stable-signed
release is the newest published one.

## v1.4

version-bump release (v1.3 code) providing the in-app update target for
on-device verification of the Settings > Update flow. Release APK.

## v1.3

in-app Settings > Update: checks the latest GitHub release, downloads the
newest APK with progress, and installs it. Version bump v1.2 → v1.3.

## v1.2

Initial public release.

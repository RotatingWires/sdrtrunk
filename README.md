![Gradle Build](https://github.com/dsheirer/sdrtrunk/actions/workflows/gradle.yml/badge.svg)
![Nightly Release](https://github.com/dsheirer/sdrtrunk/actions/workflows/nightly.yml/badge.svg)
[![Latest Release](https://img.shields.io/github/v/release/DSheirer/sdrtrunk?label=release)](https://github.com/DSheirer/sdrtrunk/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/DSheirer/sdrtrunk/total)](https://github.com/DSheirer/sdrtrunk/releases)
[![License](https://img.shields.io/github/license/DSheirer/sdrtrunk)](LICENSE)
[![Discord](https://img.shields.io/badge/chat-on%20Discord-5865F2?logo=discord&logoColor=white)](https://discord.gg/HJQaKYE)
[![support-google_groups-blue](https://img.shields.io/badge/support-google_groups-blue)](https://groups.google.com/g/sdrtrunk)

# sdrtrunk
A cross-platform java application for decoding, monitoring, recording and streaming trunked mobile and related radio protocols using Software Defined Radios (SDR).

## About this fork
This is a fork of [DSheirer/sdrtrunk](https://github.com/DSheirer/sdrtrunk) that makes the **Ignore Encrypted Calls**
option drop encrypted calls completely, so that they never hold a tuner channel that a clear call could use.  Everything
else matches upstream, and the upstream documentation linked below applies.

### Ignore Encrypted Calls
The option is in the playlist channel editor, in the **Decoder** section of a trunked channel (the control channel):

| Decoder | Setting |
|---|---|
| P25 Phase 1 and P25 Phase 2 | Ignore Encrypted Calls |
| DMR (Tier III, Capacity Plus, Connect Plus, Capacity Max) | Ignore Encrypted Calls (added by this fork) |
| NXDN | Ignore: Encrypted Calls |

It is off by default and is saved in the playlist as the `ignore_encrypted_calls` attribute of the decoder
configuration.  Playlists saved without the attribute load with the option off, and a playlist saved by this fork
still loads in upstream sdrtrunk.

When the option is on, encrypted calls are dropped in two stages:

1. **At the channel grant.**  When the channel grant (P25), voice call assignment (NXDN) or channel grant (DMR, using
   the grant's protect flag) identifies the call as encrypted, no traffic channel is tuned.  Upstream sdrtrunk already
   did this for P25 and NXDN.
2. **After tuning.**  Many encrypted calls are only identified as encrypted once the traffic channel is decoding them,
   for example when sdrtrunk joins a call in progress from a grant *update* (which carries no encryption flag), when the
   grant was missed, or when the system doesn't flag encryption in its grants.  As soon as the traffic channel detects
   encrypted audio (P25 HDU/LDU/MAC encryption parameters, DMR voice link control, NXDN voice call or initialization
   vector), it stops and its tuner channel is released.  The call is remembered as encrypted, so the grant updates that
   keep arriving for the rest of the call don't tune it again.  It is forgotten once those updates stop.

Two-timeslot channels (P25 Phase 2 and DMR) stay tuned while the other timeslot carries a clear call or data, and for 5
seconds after its last clear audio, so that a clear call that was just granted, or the pauses between the transmissions
of a clear conversation, don't get the channel released and the clear call cut up.  The channel is released once the
encrypted call has had it to itself for those 5 seconds.  Conventional (non-trunked) channels are never stopped.  Dropped calls still appear in the Events tab, labeled `IGNORED: ENCRYPTED` (or `IGNORED ENCRYPTED` for NXDN),
and `IGNORED: ENCRYPTED - TRAFFIC CHANNEL RELEASED` for P25 calls that were released after tuning.

Known limitations:
* A call is only dropped after tuning once encrypted audio is detected, so the tuner channel is in use for the first
  fraction of a second of the call.
* A clear transmission is tuned only when it can be told apart from the encrypted call that was dropped: a new channel
  grant from a different talker, a different talkgroup, or a gap of a few seconds in the grant updates.  If the same
  radio switches from encrypted to clear on the same talkgroup and channel without a gap, that transmission is missed.
* The DMR grant protect flag is what upstream sdrtrunk already shows as `ENCRYPTED` on DMR channel grants.  If a
  system sets it on clear calls, those calls are ignored too; turn the option off for that system.

### Downloading a build
The **Fork Sync and Release** workflow (`.github/workflows/fork-sync-release.yml`) runs every day at 10:23 UTC and can
also be run by hand from the Actions tab. It merges upstream `master` into this fork's `master` (the same as GitHub's
**Sync fork** button). If that commit doesn't have a release yet, it runs the tests on Windows and on Linux, builds the
Windows and Linux x86-64 zips on Linux (no ARM or Mac builds), and publishes the zips and a `SHA256SUMS` file as a
`fork-<version>` release, with the upstream changes since the previous release in its notes. The newest 10 releases are
kept. Download the zip for your system from the Releases page, unzip it, and run `bin\sdr-trunk.bat` on Windows or
`bin/sdr-trunk` on Linux. The version shown in the app is upstream's latest version tag plus the number of commits since
it and the commit id, for example `1.0.0-53-g1a2b3c4`. To run it by hand, open **Fork Sync and Release** in the Actions
tab and press **Run workflow** on `master`. Ticking **Build and release even when this commit already has a release**
rebuilds the current commit and replaces the files and notes of its existing release.

When the merge conflicts, or the tests or build fail on either system, nothing is published. GitHub emails a failed
scheduled run, and the run's summary says what to do. A conflict is resolved by merging `upstream/master` into `master`
by hand. If upstream changed its own workflow files, the workflow isn't allowed to merge them, so press **Sync fork** on
GitHub instead. The next run then builds it. Upstream's own Nightly Release and Release workflows are disabled in this
fork, so they don't publish here. Upstream's Java CI with Gradle workflow is disabled too: this workflow already tests
every release on Windows and Linux, and that one's macOS job often can't get a GitHub-hosted Mac runner, which fails its
run. GitHub pauses scheduled workflows in a repository that has had no activity for 60 days. If that happens, enable the
workflow again from the Actions tab.

### Building this fork
Building needs the BellSoft Liberica JDK 27 **Full** package (it includes JavaFX).  Packaging a release needs the exact
JDK build that `build.gradle` downloads for the bundled runtime (`jdk_download_base`, currently 27+36), because jlink
refuses any other build.  If Gradle doesn't find the JDK on its own, point Gradle at it:
```
./gradlew test -Porg.gradle.java.installations.paths=/path/to/jdk-27-full
./gradlew runtimeZipWindows -Porg.gradle.java.installations.paths=/path/to/jdk-27-full
```
The release zips are created in `build/image/`.  To pick up upstream changes by hand, fetch the `upstream` remote
(`https://github.com/DSheirer/sdrtrunk.git`) and merge `upstream/master`.

> ## 📖 [Read the sdrtrunk User Manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)
> New to sdrtrunk? Start with the **[Getting Started](https://github.com/DSheirer/sdrtrunk/wiki/Getting-Started)** guide,
> then use the **[User Manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)** and
> **[How-To Guides](https://github.com/DSheirer/sdrtrunk/wiki/How-To-Guides)** to set up tuners, playlists, aliases and streaming.

* [Help/Wiki Home Page](https://github.com/DSheirer/sdrtrunk/wiki)
* [Getting Started](https://github.com/DSheirer/sdrtrunk/wiki/Getting-Started)
* [User Manual](https://github.com/DSheirer/sdrtrunk/wiki/User-Manual)
* [How-To Guides](https://github.com/DSheirer/sdrtrunk/wiki/How-To-Guides)
* [FAQ](https://github.com/DSheirer/sdrtrunk/wiki/FAQ)
* [Download](https://github.com/DSheirer/sdrtrunk/releases)
* [Support](https://github.com/DSheirer/sdrtrunk/wiki/Support)

![sdrtrunk primary application window decoding a P25 trunked radio system](https://github.com/DSheirer/sdrtrunk/wiki/images/application_overview.png)
**Figure 1:** sdrtrunk decoding a P25 Phase 1 simulcast trunked radio system

## Download the Latest Release
All release versions of sdrtrunk are available from the [releases](https://github.com/DSheirer/sdrtrunk/releases) tab.

* **(alpha)** These versions are under development feature previews and likely to contain bugs and unexpected behavior.
* **(beta)** These versions are currently being tested for bugs and functionality prior to final release.
* **(final)** These versions have been tested and are the current release version.

## Download Nightly Software Build
The [nightly](https://github.com/DSheirer/sdrtrunk/releases/tag/nightly) release contains current builds of the software 
for all supported operating systems.  This version of the software may contain bugs and may not run correctly.  However, 
it let's you preview the most recent changes and fixes before the next software release.  **Always backup your 
playlist(s) before you use the nightly builds.**  Note: the nightly release is updated each time code changes are 
committed to the code base, so it's not really 'nightly' as much as it is 'current'.

## Minimum System Requirements
* **Operating System:** Windows (~~32 or~~ 64-bit), Linux (~~32 or~~ 64-bit) or Mac (64-bit, 12.x or higher)
* **CPU:** 4-core
* **RAM:** 8GB or more (preferred).  Depending on usage, 4GB may be sufficient.

## Creating a Release (Maintainers)
Releases are built and published automatically by the [Release](.github/workflows/release.yml) GitHub workflow
whenever a version tag is pushed to the repository.

The version is taken from the tag, so there is no version number to update in the code. Create and push a tag
with a leading `v`, following the naming convention described in `gradle.properties` (e.g. `v0.6.2-alpha-1`,
`v0.6.2-beta-1` or `v0.6.2`):
```
git tag v0.6.2-beta-1
git push origin v0.6.2-beta-1
```

Builds made from an untagged commit get a version like `0.6.2-beta-1-14-g4626d567` (14 commits after the
`v0.6.2-beta-1` tag, at commit `4626d567`), with `-dirty` appended when there are uncommitted changes.

The workflow then:
* Verifies that the version the build derives from git matches the tag and fails if it does not.
* Builds and tests the code, then creates the release zips for Linux, Mac and Windows (x86-64 and aarch64).
* Creates a GitHub release titled `Version <version>` with auto-generated release notes (a "What's Changed" list of
the PRs merged since the previous version tag), and attaches the six zip
files plus a `SHA256SUMS` checksum file.
* Marks tags containing a dash (alpha and beta versions) as pre-releases. Tags without a dash become full releases.

If the workflow fails part way through, fix the problem and re-run it from the Actions tab. It resumes from the
draft release left behind. It won't modify a release that has already been published. To rebuild a published
release, delete the release and the tag on GitHub, then push the tag again.

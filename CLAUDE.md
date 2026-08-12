# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenMCMelody is a Paper/Minecraft plugin (Kotlin) that plays MIDI files as in-game note block music. It parses Standard MIDI Files, maps each note to a General MIDI instrument/octave "slot", and plays it to targeted players either via vanilla note block sounds or a custom-built resource pack (soundpack) with real instrument samples. It also ships a small built-in HTTP control panel for browser-based control.

## Build / run commands

This is a Gradle project (Kotlin DSL) targeting Java 25 toolchain, built against the Paper API for Minecraft 26.2.

- Build the plugin jar (shadowJar runs automatically as part of `build`): `./gradlew build`
- Run a local Paper test server with the plugin installed (uses `run/` as the server dir): `./gradlew runServer`
- No test suite exists in this repository currently.

There is no separate lint task configured; rely on the Kotlin compiler (`./gradlew compileKotlin`) for correctness checks.

## Architecture

### Startup / wiring

`OpenMCMelody.kt` (the `JavaPlugin` entrypoint) constructs every manager by hand in `onEnable` and wires them together via constructor injection — there is no DI framework. This is the map to read first when tracing how a feature touches the rest of the system.

### Pipeline: MIDI file -> sound

1. **`midi` package** — `MidiParser` reads a `.sf2`-independent Standard MIDI File (`javax.sound.midi`, requires the `java.desktop` module) and merges all tracks into a single tick-ordered event stream, resolving tempo, per-channel program, CC7/CC11 volume/expression, and note-on velocity into a `ParsedSong`. A `ParsedSong` is parallel primitive arrays (tick/sound/vanillaPitch/customPitch/volume), not a `List<NoteEvent>`, since dense songs can have tens of thousands of events and several may be cached at once. Every note carries *two* pitches (vanilla vs. custom) because which one is used is decided at playback time, not parse time — the active soundpack can change while a song is cached or mid-playback.
2. Each note resolves to an `InstrumentSlot` — one slot per (GM melodic instrument × octave bucket) pair (`GmInstrumentMap`/`InstrumentSlot`/`GmNames`), plus four percussion slots. Splitting by octave lets a custom sample only need to pitch-shift within its own ~12-semitone bucket rather than across an entire instrument's range. `InstrumentSlot` instances are interned (not an enum — 1000+ constants would be unwieldy) so reference equality is safe to use for per-tick dedup.
3. **`SongCache`** memoizes parsed songs by filename + last-modified time, keyed through `CompletableFuture` so concurrent requests for the same uncached file share one in-flight parse instead of double-parsing.
4. **`playback` package** — `PlaybackManager` owns every active `PlaybackSession` and drives all of them from a single repeating 1-tick Bukkit task. A session belongs to a set of target player UUIDs (multiple players can share one session/cursor — e.g. a playlist played to a group). Per tick it batches all notes due that tick, dedupes by (slot, vanillaPitch), caps polyphony at `MAX_NOTES_PER_TICK` (loudest notes win), then plays to every online target — resolving vanilla-vs-custom sound per note via `SoundPackManager.resolve`. Playlist sessions prefetch the next song into `SongCache` a few seconds before the current one ends and transition through `PlaybackState.LOADING` while the next song's parse is still in flight.
5. All `PlaybackManager` public methods are main-thread-only, matching Bukkit command dispatch/event handlers.

### Soundpacks (`soundpack` package)

A soundpack is a `soundpacks/<name>/pack.yml` (declares any subset of the GM1 instrument slots + `basedrum`/`snare`/`hat`/`cow_bell`, each mapped to an `.ogg` file in the same folder) loaded by `SoundPackLoader`, zipped into an actual Minecraft resource pack by `ResourcePackBuilder` (`pack.mcmeta` + `assets/openmcmelody/sounds.json` + one `.ogg` per slot, all under the `openmcmelody:` namespace), and served to players by `SoundPackManager`. Only one pack is active server-wide at a time (not per-player). Building is pure CPU/file I/O and safe off-thread; activating/pushing to players touches Bukkit `Player` state and must run on the main thread. The active soundpack name is persisted to `active-soundpack.txt` and rebuilt from disk on `onEnable` so a restart doesn't silently fall back to vanilla sounds.

`soundfont/SoundFontConverter` can auto-generate a soundpack from a `.sf2`/`.dls` file: it shells out to a *separate* `java` process running `SoundFontExtractorMain` (needs `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`, which the main server JVM doesn't have) to render instruments to WAV, then to `ffmpeg` (external dependency, path configured via `soundfont.ffmpeg-path` in `config.yml`) to encode Ogg Vorbis, since the JDK can't itself produce a format Minecraft resource packs accept.

### Web control panel (`web` package)

`WebServer` uses the JDK's built-in `com.sun.net.httpserver.HttpServer` (no external HTTP dependency) on a small worker thread pool — never the Bukkit main thread. Anything touching `PlaybackManager` or live `Player` state is bounced to the main thread via `MainThreadBridge.run` (blocks the HTTP worker on a `CompletableFuture` filled by a scheduled Bukkit task; `PlaylistManager`/`SongCache` are already thread-safe and called directly). Auth (`WebAuthManager`) is in-game-code login: a browser claims a Minecraft username, gets a one-time code, and only the real player with that username can complete the login by running `/midi verify <code>` — the browser's claimed identity is never trusted on its own. The frontend is a single static file, `src/main/resources/web/index.html`, served as-is (no build step/bundler).

The `web.public-url` config value matters specifically for resource pack delivery: `web.bind` is only a bind address, but Minecraft clients download resource packs over HTTP from `public-url`, so it must be reachable by players and include the port.

### Commands (`command` package)

`MidiCommand` builds a single Brigadier command tree (`/midi ...`) registered via Paper's `LifecycleEvents.COMMANDS`. Permission gating happens per-node via `.requires { ... }` against the three permissions in `Permissions.kt` (`openmcmelody.midi.admin`/`.playlist`/`.status`, declared in `plugin.yml`). Most subcommands default target to the sender if no `target` argument is given; console has no implicit self-target and must always specify one explicitly.

### Playlists (`playlist` package)

`PlaylistManager` is global/shared storage (`playlists.yml`) editable by any player with permission — not per-player. Mutations update the in-memory `YamlConfiguration` synchronously, then hand a serialized snapshot to a single-slot async writer so rapid edits collapse into the latest snapshot instead of queueing one disk write per edit.

## Cross-cutting notes

- Everything under `midi/GmNames.kt` is deliberately Bukkit-free (no `org.bukkit.*` imports) because it's shared with `SoundFontExtractorMain`, which runs as a bare `java` subprocess outside any Paper server and can't load Bukkit-registry-backed classes.
- `run/` is a local Paper test server (world data, logs, downloaded server libraries) created by `runServer` — treat it as disposable/generated, not part of the plugin source.
- `plugin.yml`'s `version` field is templated from Gradle's `version` property via `processResources` (`build.gradle.kts`), not hardcoded.

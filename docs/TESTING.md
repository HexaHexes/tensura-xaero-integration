# Testing

What is automated, what was actually run, and what still needs a human in front
of a game client.

## 1. Commands

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"   # JDK 21 must be explicit on this machine

./gradlew build                  # compile + JUnit suite + jar
./gradlew test                   # JUnit suite only
./gradlew runGameTestServer      # headless GameTest server (6 tests)
```

`runGameTestServer` is graded by the build script on the `All N required tests
passed` log line **and** the expected count, not just on the exit code, because a
GameTest server that executes zero tests still exits successfully. `expectedTests`
in `build.gradle` must equal the number of `@GameTest` methods in
`src/gametest/java/.../ConcentrationGameTests.java` — currently `6`.

### When the harness wedges (root cause found 2026-10-27)

A healthy run takes about 30–45 s including mod loading. Occasionally the server
registered its tests (`N tests are now running`) and then never advanced: it
printed an empty `[      ]` progress line every 3–5 ms, logged no error of any
kind, and did not stop — 380 000 progress lines in eleven minutes. A rerun
always cleared it, and deleting `run/world` cleared it too.

The cause was the `empty` structure template, not the tests. Every `@GameTest`
here uses it, and it is this mod's own 8x8x8 air structure at
`src/gametest/resources/data/tensura_xaero_integration/structure/empty.nbt`
(`@PrefixGameTestTemplate(false)` keeps the name unprefixed). Its NBT stored
`size` as an int-array; `StructureTemplate` reads that key as a *list of ints*, so
the size silently came back as `0x0x0` and everything downstream treated the
structure as zero-sized:

- setup force-loads the chunks of `getStructureBoundingBox(corner, size, rotation)`
  — with size 0 the `-1` far corner is then normalised forward, giving
  `[corner .. corner+1]`;
- the pre-start gate in `GameTestInfo.tick` waits for
  `getStructureBoundingBox(structureBlockEntity)` — with size 0 that is
  `[corner-1 .. corner]`, two blocks *behind* the forced box.

When the grid corner sat on a chunk boundary the two boxes fell in different
chunks: the gate waited on a chunk nothing had forced (`hasChunk=true`,
`isPositionEntityTicking=false`, forever), `allMatch` never returned true, no test
started, and the tick loop spun. That is why it looked random (~1 run in 8, once
per aligned axis of the random test origin) and why a rerun fixed it. A
zero-sized template could never start a test whose origin was aligned.

The fix is in the template: `size` is now a proper list of ints, so it loads as
8x8x8 and the forced box and the waited-on box are the same box — every run,
whatever origin the server picks. Do not point the tests back at vanilla's
`minecraft:empty` (also zero-sized) or shrink the template to nothing.

The task is still killed on a wall-clock timeout (`240 s` by default,
`-PgametestTimeoutSeconds=...` to override), so a wedge fails the build in four
minutes instead of waiting for a human to notice. When that happens, the
`reportGametestStall` finalizer says which case it was:

```
GameTest was stopped after 240s with its tests registered but none completed -
the harness wedged, not the tests.
```

A run killed before its tests register prints nothing extra; both cases fail the
build, and the CI `gametest` job is capped at 15 minutes as a backstop for a hang
that outlives the Gradle timeout. The timeout is a backstop, not the fix: after
the template was corrected, thirteen consecutive `runGameTestServer` runs passed,
one of them with the test origin sitting exactly on a chunk boundary — the
arrangement that used to wedge every time.

Ten seconds before that kill, the build dumps every thread of the forked server to
`run/logs/gametest-thread-dump.txt` — only when the log shows its tests running and
none complete — and the `reportGametestStall` message names the file. The server is
found by the program-arguments file Gradle hands it
(`build/moddev/gameTestServerRunProgramArgs.txt`), because its launch target sits
inside that file rather than on the command line.

### Profiling runs

Two dedicated runs profile this mod's own work - one for the client half, one for the
tick half. Each has its own game directory and its own staged mods folder, so a profile
never disturbs the GameTest world:

| Run | Game directory | Mods staged | Recording |
| --- | --- | --- | --- |
| `./gradlew runProfileClient` | `run-profile/` | `profileMods`: the Tensura closure **plus both Xaero mods** | `build/profiles/client.jfr` |
| `./gradlew runProfileServer` | `run-profile-server/` | the GameTest closure - Xaero is client-only and must not load on a server | `build/profiles/server.jfr` |

`runProfileClient` matters because plain `runClient` stages neither closure: without the
Xaero jars the overlay, hover and mixin paths never execute at all. Both runs arm Java
Flight Recorder at start and write the recording when the process exits.

Three things to know before reading a recording:

- **The client run already contains the server thread.** A dev client starts an
  integrated server in the same JVM, so `Render thread` rows are the frame path (Xaero's
  per-chunk highlighter calls while it builds or repaints a map tile, plus the hover
  line) and the 20 Hz client tick (`ClientConcentration.onClientTick`), while
  `Server thread` rows are that same session's server tick paths - one process, one
  recording.
- **The dedicated server idles without a player.** `ConcentrationServer.onServerTick` and
  the Tensura chunk-tick observer both return early when nobody is subscribed, so a
  recording of a server nobody joined holds none of this mod's work. Connect with the
  pack client, or with `runProfileClient` (Multiplayer, `localhost`), to give it
  something to do. A dedicated server also needs `eula=true` in
  `run-profile-server/eula.txt` once before its first launch.
- **Both runs are the mod's own closure, not a modpack.** They prove what this mod costs
  on its own; the frame-versus-tick question inside a 400-mod pack still needs a profile
  taken from that pack.

Read a recording with the JDK 21 `jfr` tool - no viewer and no upload:

```bash
"$JAVA_HOME/bin/jfr" summary build/profiles/client.jfr
"$JAVA_HOME/bin/jfr" print --events jdk.ExecutionSample build/profiles/client.jfr \
    | grep -B3 -A3 bitwisemadness
```

To capture without quitting, dump the live recording on demand - each run prints its
recording number and the exact `jcmd` line when it starts:

```bash
"$JAVA_HOME/bin/jcmd" <pid> JFR.dump name=1 filename=build/profiles/client-now.jfr
```

No CI job uses either run.

## 2. Latest results (2026-10-27, local, Windows / Git Bash)

| Command | Result |
| --- | --- |
| `./gradlew test` | **177 tests, 0 failures, 0 errors** (14 classes) |
| `./gradlew build` | **BUILD SUCCESSFUL**, jar produced |
| `./gradlew runGameTestServer` | **6 tests ran, all passed** — `All 6 required tests passed :)`, task reported `GameTest OK: all 6 tests passed` |

These are the only runs backing the claims in this repository. Nothing
client-rendering related was verified — see §6.

## 3. JUnit suite (177 tests)

All of these compile and run without Minecraft because the logic sits in
Minecraft-free classes.

| Class | Covers |
| --- | --- |
| `concentration/ConcentrationThresholdsTest` | band classification, exact boundaries (inclusive lower band), baseline-relative maths, normal↔elevated transitions both ways, invalid baseline and non-finite input, threshold ordering validation |
| `concentration/OverlayPaletteTest` | default palette, `#RRGGBB` parsing incl. malformed input, opacity clamping, Xaero colour encoding (`blue<<24 \| green<<16 \| red<<8 \| alpha`), zero-alpha normal chunks |
| `cache/CacheKeyAndCacheTest` | key isolation across world token, dimension and coordinates; value/replace/invalidate/clear; missing-data vs valid-zero distinction; bounded size and eviction |
| `cache/RegionKeyMemoTest` | the memoised region key: a repeat ask returns the same instance, a different region, dimension or sign rebuilds, one entry only |
| `refresh/RefreshSchedulingTest` | `UpdateQueue` change detection and deduplication, per-chunk minimum interval (delayed, never dropped), bounded drain, `retainOnly` pruning, `ReconcileCursor` rotation and budget covering the whole set |
| `refresh/CadenceEstimateTest` | the server's cadence promise: the nominal interval before it has measured itself, a stall widening it (never below nominal), the worst recent sweep winning, the pause cap, recovery forgetting a stall, zero-length and impossible samples rejected |
| `client/ClientConcentrationStoreTest` | mapped-only ingress (an unmapped chunk cannot be stored even when offered), server-supplied bands (including an unbanded value), live→last-known demotion and promotion, the wall-clock freshness window derived from the server's measured cadence (including a stalled server), stale/unavailable handling, replay that never overwrites live data and keeps the stored band, region version bumps on visible changes only, `clearServerInfo` — all on an injected clock, so no test sleeps |
| `tiers/TierParserTest` | datapack schema: baseline-only and per-dimension ladders, multiple dimensions per file, palette files, partial or non-increasing ladders rejected, unlisted dimensions falling back, unclassifiable dimensions, broken files skipped without emptying the table |
| `cache/ConcentrationFileFormatTest` | write/read round trip, empty file, wrong magic, unknown version (incl. a version-1 file), row limit, negative count, non-finite value, impossible band code, truncation, invalid row construction |
| `client/ConcentrationStorageTest` | save and load a world, queued save plus flush, a save that must be visible before it returns, a zero-deadline flush, missing and corrupt files read as no history, per-world name uniqueness and sanitising (no path escape) |
| `client/PauseCreditTest` | which part of a tick gap counts as paused time: a gap paused at both ends in full, a pause that begins inside a stall capped at one tick, a gap the server ran through not at all, a frozen pause credited on resume, a stopped or backwards clock credited nothing |
| `net/PackedChunkPosTest` | packing/unpacking incl. negative and extreme coordinates |
| `util/ChunkRadiusTest` | the circular request radius: the player's own chunk, exactly-on-radius included, one chunk past and square corners excluded, centred on the player, no overflow at a million chunks, negative radius rejected |
| `util/ConcentrationTextTest` | hover line formatting, number precision, with and without a classification, unavailable state |

## 4. GameTest suite (6 tests)

Runs on a headless server with the real Tensura jar and its dependency closure on
the classpath (staged into `run/mods` from CurseMaven by `prepareGametestMods`), so
these exercise the actual `ChunkStorage` API rather than a stub.

| Test | Asserts |
| --- | --- |
| `baseline_is_readable` | Tensura's configured baseline is reachable through the bridge and positive |
| `loaded_chunk_has_a_concentration` | a loaded chunk answers with a finite, non-negative authoritative value |
| `unloaded_chunk_is_unavailable` | an unloaded chunk reports *unavailable*, and reading it does not load it |
| `coordinator_ticks_without_players` | the coordinator is started by the server lifecycle, ticks with no players without throwing, and holds no subscriptions |
| `tier_datapack_is_loaded` | the datapack reaches the server: dimensions are described, the overworld's described baseline is used and 600 classifies as elevated, and `tensura:hell` gets its own ladder instead of the default |
| `server_config_is_usable` | the server config is attached and its bounds are positive |

## 5. What is not automated

- **Client rendering, overlays and hover.** Xaero's mods are client-only and
  cannot be loaded in a GameTest server; there is no headless substitute for
  "did the map paint". Everything downstream of the client cache is verified by
  the manual matrix below only.
- **Packet round trips to a real client.** Payload codecs and server-side
  validation are exercised structurally, but no test drives an actual client
  connection.
- **The two mixins.** They are client-only and depend on classes that exist only
  when Xaero is installed; they can be proven only by a client start.
- **Persistence wiring.** The codec and the file store are covered directly, but
  the session hooks (save on dimension change, replay on join) are client-only
  code and are verified by the manual matrix rows 14 and 17.

## 6. Manual test matrix

Each row is a scenario a human must run in a real client. "Both Xaero mods" is the
default setup unless stated.

| # | Scenario | Expected |
| --- | --- | --- |
| 1 | Mapped chunk at normal concentration | no tint; world-map hover shows the value and *Normal* |
| 2 | Mapped chunk with elevated concentration (e.g. a magic engine cluster) | tinted in the band colour; hover shows value and the band name |
| 3 | Watch a chunk while its concentration changes over time | tint opacity/colour steps through bands; no flicker; hover follows |
| 4 | **Unmapped chunk with known elevated concentration** (stand near it without mapping it) | no tint, no hover line, nothing revealed by either map |
| 5 | Explore so a chunk becomes mapped | overlay appears on the next subscription interval (≤ ~1 s) with the current value |
| 6 | Concentration changes caused by entities | overlay updates without reopening the map |
| 7 | Concentration changes caused by blocks | same as 6 |
| 8 | Different dimensions (Overworld / Nether / End) | each keeps its own values; changing dimension never shows the old dimension's data |
| 9 | Different worlds / save files | no bleed of values between saves |
| 10 | Multiplayer (two clients, one server) | each client sees only its own mapped chunks; server log shows no unrequested sends |
| 11 | Minimap only installed | minimap overlay works; world map absent, no crash, no log spam |
| 12 | World map only installed | world-map overlay and hover work; minimap absent, no crash |
| 13 | Both Xaero mods installed | both overlays and hover work at once |
| 14 | Missing/unavailable data (walk far from a mapped chunk, or switch `sync.enabled` off) | the value becomes *last known*: dimmer tint, hover line says "(last known)", nothing invents a number |
| 15 | World unload and reconnection | cache cleared, values re-requested, no live value carried over from the previous session |
| 16 | Large mapped area (hundreds of chunks mapped) | client stays smooth; `reconcileBudgetChunksPerPlayer` bounds server work; no spike on dimension change |
| 17 | Relaunch the game and rejoin the same world | previously mapped chunks show dimmed last-known values immediately, then brighten as the server answers for the ones now loaded; a different world shows nothing from the first |

For 4, run with `F3` open to confirm the chunk really is unmapped in Xaero, and
check the client log for subscription packets — an unmapped chunk must not appear
in any request.

## 7. CI and release

`.github/workflows/ci.yml` runs three checks on every pull request:

- **junit** — `./gradlew build`, then asserts the test-results directory exists and
  that the total test count is non-zero, so the check cannot pass vacuously.
- **gametest** — `./gradlew runGameTestServer`, graded on the
  `All N required tests passed` line with `N > 0`.
- **version-bump** — a beta/stable bump must change `gradle.properties` and
  nothing else; an `-alpha` bump may bundle work.

No credentials are needed: every dependency resolves from CurseMaven.

Recorded runs on `main`:

| Run | Result |
| --- | --- |
| `40248933354` | **failed** — every step died on `./gradlew: Permission denied`: the wrapper was committed from a Windows checkout as mode `100644`. Fixed by committing it as `100755`. |
| `40249002276` | **success** — `junit` (`102 tests, all passing`), `gametest` (5 tests, graded on the log line), `version-bump` (no bump, alpha allowed). |

The release workflow re-asserts both suites before it tags anything, so a release
cannot be published from a tree whose tests did not run.

## 8. Reporting rules

- A green `./gradlew build` proves compilation and the JUnit suite. It does not
  prove the overlay draws.
- A green `runGameTestServer` proves the server-side reader, unloaded-chunk guard
  and coordinator lifecycle. It does not prove anything client-side.
- Anything in §6 that has not been performed must be reported as untested, not as
  working.

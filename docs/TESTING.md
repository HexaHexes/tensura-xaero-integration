# Testing

What is automated, what was actually run, and what still needs a human in front
of a game client.

## 1. Commands

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"   # JDK 21 must be explicit on this machine

./gradlew build                  # compile + JUnit suite + jar
./gradlew test                   # JUnit suite only
./gradlew runGameTestServer      # headless GameTest server (5 tests)
```

`runGameTestServer` is graded by the build script on the `All N required tests
passed` log line **and** the expected count, not just on the exit code, because a
GameTest server that executes zero tests still exits successfully. `expectedTests`
in `build.gradle` must equal the number of `@GameTest` methods in
`src/gametest/java/.../ConcentrationGameTests.java` — currently `5`.

If a run sits at the `[ ++++ ]` progress line and never prints the summary, the
dev run directory is stale rather than a test failing: delete `run/world` and run
again. This was observed once locally and did not reproduce after clearing it; the
CI job starts from a clean checkout, so it cannot hit it.

## 2. Latest results (2026-10-25, local, Windows / Git Bash)

| Command | Result |
| --- | --- |
| `./gradlew test` | **102 tests, 0 failures, 0 errors** (9 classes) |
| `./gradlew build` | **BUILD SUCCESSFUL**, jar produced |
| `./gradlew runGameTestServer` | **5 tests ran, all passed** — `All 5 required tests passed :)`, task reported `GameTest OK: all 5 tests passed` |

These are the only runs backing the claims in this repository. Nothing
client-rendering related was verified — see §6.

## 3. JUnit suite (102 tests)

All of these compile and run without Minecraft because the logic sits in
Minecraft-free classes.

| Class | Covers |
| --- | --- |
| `concentration/ConcentrationThresholdsTest` | band classification, exact boundaries (inclusive lower band), baseline-relative maths, normal↔elevated transitions both ways, invalid baseline and non-finite input, threshold ordering validation |
| `concentration/OverlayPaletteTest` | default palette, `#RRGGBB` parsing incl. malformed input, opacity clamping, Xaero colour encoding (`blue<<24 \| green<<16 \| red<<8 \| alpha`), zero-alpha normal chunks |
| `cache/CacheKeyAndCacheTest` | key isolation across world token, dimension and coordinates; value/replace/invalidate/clear; missing-data vs valid-zero distinction; bounded size and eviction |
| `refresh/RefreshSchedulingTest` | `UpdateQueue` change detection and deduplication, per-chunk minimum interval (delayed, never dropped), bounded drain, `retainOnly` pruning, `ReconcileCursor` rotation and budget covering the whole set |
| `client/ClientConcentrationStoreTest` | mapped-only ingress (an unmapped chunk cannot be stored even when offered), live→last-known demotion and promotion, stale/unavailable handling, replay that never overwrites live data, region version bumps on visible changes only, `clearBaseline` |
| `cache/ConcentrationFileFormatTest` | write/read round trip, empty file, wrong magic, unknown version, row limit, negative count, non-finite value, truncation, invalid row/baseline construction |
| `client/ConcentrationStorageTest` | save and load a world, queued save plus flush, missing and corrupt files read as no history, per-world name uniqueness and sanitising (no path escape) |
| `net/PackedChunkPosTest` | packing/unpacking incl. negative and extreme coordinates |
| `util/ConcentrationTextTest` | hover line formatting, number precision, with and without a classification, unavailable state |

## 4. GameTest suite (5 tests)

Runs on a headless server with the real Tensura jar and its dependency closure on
the classpath (staged into `run/mods` from CurseMaven by `prepareGametestMods`), so
these exercise the actual `ChunkStorage` API rather than a stub.

| Test | Asserts |
| --- | --- |
| `baseline_is_readable` | Tensura's configured baseline is reachable through the bridge and positive |
| `loaded_chunk_has_a_concentration` | a loaded chunk answers with a finite, non-negative authoritative value |
| `unloaded_chunk_is_unavailable` | an unloaded chunk reports *unavailable*, and reading it does not load it |
| `coordinator_ticks_without_players` | the coordinator is started by the server lifecycle, ticks with no players without throwing, and holds no subscriptions |
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

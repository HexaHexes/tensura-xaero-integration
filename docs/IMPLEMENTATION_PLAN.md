# Implementation plan

Phased plan with dependencies, tests and acceptance criteria. Every phase below is
complete except publication; the authoritative record of what was actually run
lives in [`TESTING.md`](TESTING.md), and the component names here are the ones in
the tree.

## Phase 0 — Repository bootstrap ✅

- [x] Verify `HexaHexes/tensura-xaero-integration` is available (`gh repo view` → not found).
- [x] Verify GitHub auth and repository permission (`gh auth status`: `repo`, `workflow`).
- [x] Create the empty public remote (no template history, no template remote).
- [x] Seed a fresh local repository from the template working tree (not its `.git`).
- [x] Rename every template identifier (`mod_id`, package, mixin config, GameTest structure).
- [x] Point `origin` at the new repository.

Acceptance: `git remote -v` shows only `HexaHexes/tensura-xaero-integration`,
`git log` has no template commits, no `examplemod` string remains. — met.

## Phase 1 — Build and dependency setup ✅

Depends on: Phase 0.

- [x] `gradle.properties` identity (mod id/name/group/version) and pinned dependency versions.
- [x] CurseMaven coordinates for Tensura, ManasCore, Architectury, Xaero Minimap, Xaero World Map.
- [x] `compileOnly` for every mod API (never bundled); runtime closure for the GameTest server.
- [x] `neoforge.mods.toml` with the three optional dependencies + two client mixin configs.
- [x] `extractManascoreModules` so Tensura's ManasCore-derived public types compile.

Acceptance: `./gradlew compileJava` succeeds from a clean checkout with no local jars. — met.

## Phase 2 — Pure core ✅

Depends on: Phase 1.

- [x] `cache/ChunkCacheKey` (world token, dimension, chunk x/z) and `ChunkCacheKey`/`net/PackedChunkPos` packing.
- [x] `concentration/ConcentrationThresholds` + `ConcentrationClassification` (baseline-relative tiers,
      configurable percentages, boundary behaviour).
- [x] `concentration/OverlayPalette` (colour parsing, per-tier ARGB, Xaero colour encoding).
- [x] `cache/ConcentrationCache` (bounded, timestamped, invalidatable) + `cache/ConcentrationEntry`.
- [x] `refresh/UpdateQueue` (dedupe, per-chunk minimum interval, bounded drain, retention pruning).
- [x] `refresh/ReconcileCursor` (bounded rotating scan over a subscription set).
- [x] `client/ClientConcentrationStore` (mapped-only ingress, staleness, region versions).

Acceptance: JUnit suite covers classification, thresholds incl. exact boundaries,
colour/opacity, cache key isolation, invalidation, dedupe, staleness, reconciliation
scheduling, mapped-only ingress, and normal↔elevated transitions. — met (74 tests).

## Phase 3 — Tensura server integration ✅

Depends on: Phase 1, Phase 2.

- [x] `tensura/TensuraBridge` (no Tensura imports; `ModList` gate; `try/catch` degradation).
- [x] `tensura/TensuraAccess` (only class importing Tensura; `hasChunk` guard →
      `TensuraStorages.getChunkFrom` → `getMagicule`, plus the configured baseline).
- [x] `tensura/TensuraTickObserver` (`TensuraLevelEvents.CHUNK_TICK_POST`, guarded registration).

Acceptance: GameTest reads a loaded chunk's value, reports an unloaded chunk as
unavailable, never loads a chunk, and reads the baseline. — met.

## Phase 4 — Networking and hybrid refresh ✅

Depends on: Phase 3.

- [x] `net/SubscriptionPayload` (C2S) with batched packed chunk positions and subscribe flag.
- [x] `net/ConcentrationPayload` (S2C) with packed positions + values (`NaN` = unavailable).
- [x] `net/ServerInfoPayload` (S2C) carrying the authoritative baseline and the server's refresh cadence.
- [x] `server/ConcentrationServer`: per-player subscriptions, radius/loaded validation,
      dedupe, change-only updates, bounded reconciliation, flush-per-tick batching,
      logout cleanup, world-unload cleanup.
- [x] `client/ClientConcentration` subscription driving: mapped-chunk diff, batching,
      dimension/world resets, periodic full re-declaration.

Acceptance: GameTest covers the coordinator lifecycle and the reader; the
privacy and scheduling invariants are asserted in unit tests where the logic is
pure. Full packet round trips to a real client remain manual (see `TESTING.md` §5).

## Phase 5 — Client overlays and hover ✅

Depends on: Phase 4.

- [x] `client/WorldMapBridge` + `client/MinimapBridge` (no Xaero imports; separate gates).
- [x] `client/XaeroWorldMapAccess` / `client/XaeroMinimapAccess` (the only classes importing
      Xaero; mapped-chunk checks, region refresh, session lookup).
- [x] World-map highlighter: region hash, region/chunk gates, colours, subtle/blunt
      tooltips for the hovered chunk.
- [x] Minimap highlighter: region/chunk gates, colours.
- [x] Two client mixins registering each highlighter at `HighlighterRegistry.end()`.
- [x] Region invalidation for both maps, once per client tick.

Acceptance: `./gradlew build` succeeds; client classes are only reachable from
`Dist.CLIENT` entry points; manual in-game checklist in `docs/TESTING.md`. — build
met, manual checklist **not yet executed** (no client pass was run).

## Phase 6 — Configuration ✅

Depends on: Phase 5.

- [x] Client config: overlay toggles, hover toggle, thresholds, colours, opacity,
      staleness/purge, subscription radius/interval/batch size, baseline fallback.
- [x] Server config: sync enable, reconciliation interval/budget, per-player
      subscription cap, distance cap, minimum per-chunk update interval, packet cap,
      subscription packet rate limit.

Acceptance: all keys documented in `docs/CONFIGURATION.md`; defaults are what the
GameTest and unit tests use. — met.

## Phase 7 — Tests, docs, CI ✅

Depends on: Phase 6.

- [x] JUnit suite (non-vacuous) and GameTest suite (`expectedTests` in sync).
- [x] `README.md`, `docs/*.md`, `CHANGELOG.md`, `agents/AGENTS.md`.
- [x] CI covers this repo's test tasks; no credentials in workflows.

Acceptance: `./gradlew build` and `./gradlew runGameTestServer` pass locally; no
generated output, IDE state, secrets or third-party jars are staged. — met.

## Phase 8 — Last-known persistence ✅

Depends on: Phase 5, Phase 6.

- [x] `cache/ConcentrationFileFormat`: strict binary codec for one world's values.
- [x] `client/ConcentrationStorage`: per-world file, atomic save, daemon writer, corruption tolerated.
- [x] `ClientConcentrationStore`: `live` / `last known` states, demotion on unsubscribe and
      unavailability, replay that never overwrites live data.
- [x] Session hooks: save on dimension change, world change and logout; replay on join;
      30-second periodic save.
- [x] Dimmed overlay for last-known values plus a `(last known)` hover line.
- [x] Config: `freshness.historicalOpacityScale`, `[persistence] enabled`,
      `maxEntriesPerWorld`; `freshness.purgeAfterTicks` removed.
- [x] JUnit coverage: codec, file store, store states (102 tests total).

Acceptance: `./gradlew build` and `./gradlew runGameTestServer` pass; a chunk that
unloads keeps a dimmed last-known tint and a labelled hover line rather than
disappearing or pretending to be current. — build and tests met; the client-side
dimming is part of the manual pass in `docs/TESTING.md` rows 14 and 17.

## Phase 9 — Publish ⏳

Depends on: Phase 7 and explicit user go-ahead.

- [x] Commit in reviewable slices with descriptive subjects.
- [x] Push `main` to `origin`.
- [x] CI green on `main` (`junit`, `gametest`, `version-bump`).
- [x] Alpha release published by `release.yml`:
      `v0.1.0-alpha.2+gbf0c331`.
- [ ] Manual in-game pass through `docs/TESTING.md` §6 — the only outstanding item.

The remote holds `main` and one tag; the local tree is in sync with it. The alpha
release is a pre-release with the jar attached, produced by the same workflow that
will tag later versions.

# Repo rules — Tensura Xaero Integration

Repo-specific rules and notes. The org-wide `AGENTS.md` beside this file is
maintained in [`neoforge-mod-template`](https://github.com/HexaHexes/neoforge-mod-template)
and pulled in verbatim - **never hand-edit it.** Everything in this file is this
repo's own and is edited freely; keep it aligned with the org rules rather than
restating them.

**Repo:** `HexaHexes/tensura-xaero-integration` · **Mod ID:** `tensura_xaero_integration` · **Branch:** `main`
**Platform:** Minecraft 1.21.1 / NeoForge 21.1.259 / JDK 21 · **Jar:** `build/libs/tensura_xaero_integration-<version>.jar`

Shows Tensura: Reincarnated's ambient chunk magicule as a translucent overlay on
Xaero's Minimap and Xaero's World Map, and reports the hovered chunk's
concentration on the world map. Server-authoritative, mapped chunks only, no
simulation of its own. Read `docs/RECONNAISSANCE.md` before touching any
third-party API — it records what those jars actually expose and what they do not.

**Dependencies**
- Required: Minecraft, NeoForge.
- Optional: `tensura` (2.0.1.3+, the only concentration source), `xaerominimap`
  (26.6.0+), `xaeroworldmap` (1.47.0+) — all `compileOnly` from CurseMaven, never
  bundled. Gating: `tensura/TensuraBridge` and `client/WorldMapBridge` /
  `client/MinimapBridge` check `ModList` first, and `TensuraAccess` /
  `Xaero*Access` are the only classes importing those APIs. Do not import a
  third-party type anywhere else.

**Test:** `./gradlew runGameTestServer` — `ConcentrationGameTests` (6 tests).
`expectedTests = 6` in `build.gradle` must match the `@GameTest` count. JUnit:
`./gradlew test` (177 tests, no Minecraft needed). JFR-armed profiling runs:
`./gradlew runProfileClient` (both Xaero mods staged) and `./gradlew runProfileServer`
(the server closure); see `docs/TESTING.md`.

**Remote:** `github.com/HexaHexes/tensura-xaero-integration` (public, default
branch `main`).

---

## Gotchas (do not regress them)

- **Two mixins are load-bearing.** `mixin/worldmap/WorldMapHighlighterRegistryMixin`
  and `mixin/minimap/MinimapHighlighterRegistryMixin` inject at `HEAD` of each
  map's `HighlighterRegistry.end()` because neither Xaero mod exposes a
  third-party registration call. Verified only against the pinned Xaero versions;
  re-verify on any bump, and keep each mixin config's `requiredMods` pointing at
  exactly one map mod so it never applies when that map is absent.
- **`compileOnly` versions and the `*_mod_version` ranges in
  `src/main/templates/META-INF/neoforge.mods.toml` are one decision.** Bump the
  file id and the version together in `gradle.properties`.
- **ManasCore's modules are FML nested jars.** `extractManascoreModules` explodes
  them onto the compile classpath; without it Tensura's public types do not
  compile. Keep that input coming from CurseMaven.
- **Never substitute `AreaMagiculeHelper.getMagicule` for a chunk query** — it
  returns the baseline when the chunk is not loaded, which turns "no data" into a
  fake value.
- **The gametest `empty` template must stay non-zero-sized.**
  `src/gametest/resources/data/tensura_xaero_integration/structure/empty.nbt` is an
  8x8x8 air structure that declares `size` as a list of ints. A zero-sized
  template — vanilla's `minecraft:empty`, or this file with a `size` written as an
  int-array — makes the box the harness force-loads differ from the box it waits
  on, and the run hangs forever whenever the random test origin is chunk-aligned.
  `docs/TESTING.md` has the full account.
- **Privacy rule:** a chunk that is not mapped must never be rendered, tooltipped,
  cached or servery. `ClientConcentrationStore.offer` refuses unmapped writes and
  `ConcentrationServer` only answers subscribed chunks — do not add a path around
  either gate.
- **Last-known values are still real observations.** Persistence only ever writes
  what the mapped-only ingress already accepted, and a replayed value is always
  `historical`, so it is drawn dimmer and hover says "(last known)". Never store a
  value for an unmapped or never-visited chunk, and never let a replayed value
  compare equal to a live one (the cache treats `historical` as part of the
  observation for exactly that reason).

## Scope doctrine - repo additions

- **Mixins are sanctioned here, narrowly:** the two client mixins above are the
  only way to register a map overlay (neither Xaero mod exposes a registration
  call). No other mixin is to be added here; the org rule "public API only, no
  mixins" still applies to new addon repos.

## Naming - this repo

- Repo and folder: `tensura-xaero-integration`; `mod_id`:
  `tensura_xaero_integration`; Java base package
  `org.bitwisemadness.tensuraxaerointegration`, with the Maven `group` in
  `build.gradle` matching.

## CI & release model - this repo

- `ci.yml` runs the template's three checks (junit, gametest, version-bump).
- The repo ships `release.yml`: a version in `gradle.properties` without a
  matching `vX.Y.Z` tag is built and published as a GitHub release
  (alpha/nightly opt-in via the `ALPHA_RELEASES` variable, off by default).

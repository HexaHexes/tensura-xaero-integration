# Changelog

All notable changes to this project. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
semantic versioning with pre-release suffixes.

## [0.1.0-alpha.4] — 2026-10-26

Settled chunks staying bright: an unchanged concentration is confirmed again
instead of going stale and dimming to "last known".

### Fixed

- **Live values dimmed after 30 seconds even in loaded chunks.** The server only
  sent a chunk when its value *changed*, and a chunk parked at its magicule
  ceiling never changes - so it was sent once, on subscription, and never again.
  The client then correctly treated it as no longer refreshed, dimmed it and
  labelled it last known. The server now re-confirms an unchanged value every
  `refreshIntervalTicks` (200 ticks / 10 s by default), which keeps the overlay
  at full strength for as long as the chunk is honestly answerable. Only
  subscribed, currently loaded chunks are re-read, and the sweep is bounded like
  reconciliation.

## [0.1.0-alpha.3] — 2026-10-26

An overlay that never appears because the server could not report a baseline.

### Fixed

- **`baseline.fallback` was documented but never used.** When the server reports
  an unreadable baseline (`NaN`) the client now classifies against the configured
  fallback instead of against nothing. Previously every band disappeared, and
  with them the entire overlay, while the hover line still showed a number - the
  exact "tooltip works, map stays grey" symptom.
- The server retries an unreadable baseline every 100 ticks instead of waiting
  the full 600-tick refresh, so a transient read failure no longer costs 30
  seconds of missing colours.

### Added

- The resolved baseline is logged once per session (`Tensura baseline for this
  world: 500.0`, or a warning naming the fallback), and the world-map overlay logs
  the first chunk it draws and the first chunk it actually paints. Both make
  "nothing is visible" answerable from a log instead of guesswork.

## [0.1.0-alpha.2] — 2026-10-25

Last-known concentrations: mapped chunks keep what was last observed there after
they unload, dimmed and labelled as last known.

### Added

- **Per-world concentration history.** Values are saved to
  `<game dir>/tensura_xaero_integration/<world>-<hash>.bin` (all dimensions in one
  file) every 30 seconds and whenever the session ends or changes dimension, then
  replayed on join. Only chunks that passed the mapped-only ingress are ever
  written, so a replay cannot reveal a chunk this client had not mapped.
- **Last-known display state.** A value the server stops refreshing is kept and
  drawn at `freshness.historicalOpacityScale` (default 45% of its band's opacity);
  the hover line appends `(last known)` instead of presenting it as current.
- **`[persistence]` client config** (`enabled`, `maxEntriesPerWorld`) and
  `freshness.historicalOpacityScale`.

### Changed

- A chunk leaving the request radius or unloading is now **demoted** to last
  known instead of being forgotten, so an explored area stays readable.
- `freshness.purgeAfterTicks` was removed: memory is bounded by
  `persistence.maxEntriesPerWorld` rather than by dropping remembered values.

### Fixed

- A value that alternated between current and remembered did not repaint the map,
  because the two states compared as the same observation.

## [0.1.0-alpha.1] — 2026-10-25

First alpha. Server-side concentration reading, hybrid refresh, both map
overlays and world-map hover, with mapped-chunk-only disclosure.

### Added

- **Server-side concentration reader.** Reads Tensura: Reincarnated's chunk
  magicule through `ChunkStorage.getMagicule()`, behind a `hasChunk` guard so an
  unloaded chunk is reported *unavailable* instead of being replaced by the
  configured baseline. No chunk is ever loaded or generated to answer a query.
- **Mapped-only synchronisation.** Clients declare the chunks their maps have
  actually mapped; the server answers only those, only within a radius cap, only
  in the player's current dimension, only for loaded chunks, and only at a
  bounded rate. Unmapped chunks are never disclosed by rendering, tooltips,
  cache contents or packets.
- **Hybrid refresh.** Event-driven updates from Tensura's `CHUNK_TICK_POST`, with
  a rotating bounded periodic reconciliation sweep as the safety net. Change
  detection, per-chunk send throttling and per-tick batching keep both work and
  packet volume bounded.
- **Overlay on Xaero's Minimap and Xaero's World Map.** Baseline-relative bands
  with rising opacity, one shared palette so the two maps cannot disagree.
  Regions repaint only when a value in them changed.
- **World-map chunk hover.** Shows the hovered chunk's concentration (never the
  player's chunk) and its relative band.
- **Configuration.** Client file for overlays, thresholds, palette, freshness and
  subscription radius; server file for the sync switch, caps, reconciliation
  budget and packet rate.
- **Graceful degradation.** Works with either map alone, with neither, and
  without Tensura — no crash in any combination.
- **Tests.** 74 JUnit tests over the Minecraft-free core; 5 GameTests against the
  real Tensura jar for the reader, the unloaded-chunk guard and the coordinator
  lifecycle.
- **Docs.** Reconnaissance, implementation plan, architecture, configuration,
  compatibility and testing notes.

### Known limitations

- Chunks that are no longer loaded server-side show no overlay and no value.
  Tensura has no value for an unloaded chunk and this mod never forces one.
- Data is therefore limited to the neighbourhood of the player, not the whole
  mapped history.
- Overlay registration needs one mixin per map, because neither Xaero mod offers a
  third-party registration call in the pinned versions. Verified only against
  Xaero's Minimap 26.6.0 and World Map 1.47.0.
- The bands are presentation choices; Tensura defines a baseline, not named
  concentration tiers.
- Client rendering and both mixins are verified only by a manual in-game pass,
  not by CI.

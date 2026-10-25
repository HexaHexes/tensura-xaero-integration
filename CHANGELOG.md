# Changelog

All notable changes to this project. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
semantic versioning with pre-release suffixes.

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

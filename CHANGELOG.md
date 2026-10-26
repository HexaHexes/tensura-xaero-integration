# Changelog

All notable changes to this project. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
semantic versioning with pre-release suffixes.

## [0.1.0-alpha.8] — 2026-10-26

Values stopped flickering between current and last known on a busy server.

### Fixed

- **Live values dimmed and came back on servers that could not keep up.** The
  freshness window was counted in client ticks while the heartbeat was counted in
  server ticks, so the two clocks were compared as if they were the same. On a pack
  heavy enough to log `Can't keep up! ... 120 ticks behind`, 200 server ticks take
  far longer than 200 client ticks, the window expired between two confirmations,
  and the value flipped to last known until the next one arrived - including for the
  chunk you were standing on. Freshness is now measured in **milliseconds of real
  time** on the client, and the server reports how long one of its confirmations
  actually takes (measured, not assumed), so a slow server widens its own window.
- The window is now three confirmations rather than two. One beat of slack is not a
  margin: a single multi-second stall consumed it.
- `ServerInfoPayload` carries the measured cadence in milliseconds alongside the tick
  count; protocol version is now 4.

### Added

- One-shot log lines that name why a value went last known, so the next flicker
  report answers itself: a confirmation gap (`no confirmation for N ms`), a chunk
  the server could not read, or a chunk that left the request range.

### Changed

- `ConcentrationEntry` timestamps are wall-clock milliseconds, and the client store
  takes an injectable clock, so the tests describe a stalled server without sleeping.

Tier entries for the dimensions the Tensura RPG pack actually has.

### Added

- Datapack entries for `iceandfire:dread_land` (`+50000` level modifier),
  `mysticism:elemental_realm` (`+100000`, tight `1.005`–`1.03` ladder for its seven
  biomes), `mysticism:kamui_dimension`, `irons_spellbooks:pocket_dimension` and
  `tensura_all_the_adventure:reincarnation_trial`.
- Enumerated from the pack's own jars, not guessed: those five plus the three
  vanilla dimensions and the three Tensura ones are the complete set their
  `data/<ns>/dimension` files define.

### Fixed

- `tensura:labyrinth` no longer carries a tight ladder it cannot use: the dimension
  is a flat void world, so every chunk sits at its normal and the default ladder is
  correct. Its magic engines subtract magicule, which is below normal and therefore
  unpainted either way.

### Changed

- Version bump only otherwise: no protocol or schema change from 0.1.0-alpha.6.

Per-dimension tiers from a datapack, decided by the server.

### Added

- **A dimension has its own normal.** Tensura's data puts the overworld at the bare
  baseline, the nether at `1.2x` plus up to `+4500` per biome, the end at `1.5x`
  plus `+4500`, and `tensura:hell` at `+100000`. One global baseline therefore
  painted the nether and the end as a single flat "exceptional" colour and made the
  ladder meaningless there. Bands are now computed against the dimension's own
  baseline, and `tensura:hell` and `tensura:labyrinth` carry their own ladders
  because their ranges are tight.
- **Datapack-driven, server-authoritative tiers.** `data/<namespace>/magicule_tiers/*.json`
  declares each dimension's baseline and optional band multipliers; a file may
  instead carry the default `palette`. Packs merge, `/reload` re-reads, a broken
  file is logged and skipped, and a dimension no pack describes still falls back to
  Tensura's baseline. Shipped: overworld, nether, end, hell, labyrinth, boss area.
- The server now classifies each chunk and sends the band with the value, so two
  players on one server cannot disagree about a chunk's tier and no client config
  can change it. The tier datapack is read on the server only.

### Changed

- **The client's `[thresholds]` and `[baseline]` sections are gone.** Bands are the
  server's; multiplying a client-side baseline is exactly the second opinion this
  removes. Leftover keys in an existing config file are ignored.
- **`[palette]` colours now default to empty and opacities to `-1`,** both meaning
  "use the datapack's value". Set a colour to override that band locally.
- The history file is now version 2 and stores the band per row, so a replayed map
  is painted the way it was observed rather than against today's tiers. Version 1
  files are rejected as "no history" instead of being misread. There is no
  migration: the map refills from the server as you play.
- `ConcentrationPayload` carries a band per chunk and `ServerInfoPayload` carries
  the palette; protocol version is now 3.

One freshness rule instead of two: the client's "still current" window now comes
from the server's own refresh cadence.

### Changed

- **`freshness.staleAfterTicks` is gone from the client config.** It could not be
  right in general: the client has no way to know how often a given server will
  confirm a chunk, so a hand-tuned number either dimmed values the server was
  still sending or kept claiming currency for values it had stopped sending. The
  window is now twice the server's `refresh.refreshIntervalTicks` (floored at 100
  ticks), so raising the server's interval keeps values current for longer with
  no client edit. Anything left over in an existing config file is ignored.
- `BaselinePayload` became `ServerInfoPayload`, carrying the baseline **and** the
  refresh interval. Protocol version is now 2, so a mismatched client and server
  refuse each other instead of decoding garbage.
- The join log line now names the cadence in force: `Tensura baseline for this
  world: 500.0 (server confirms chunks every 200 ticks, so values count as
  current for 400 ticks)`.

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

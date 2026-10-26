# Changelog

All notable changes to this project. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
semantic versioning with pre-release suffixes.

## [0.1.0-alpha.12] — 2026-10-26

No behaviour change: the map paint path and the expiry sweep stop allocating and
stop fighting over one lock.

Xaero asks a highlighter about every visible chunk when it (re)builds a map tile,
not on every frame; only the hover line is a per-frame query, and that is memoised
separately below.

### Changed

- **The palette is resolved once, not once per chunk.** Both highlighters asked
  `ClientConfig.palette` for every visible chunk on every tile rebuild, which built
  a palette, four tier styles and performed eight config lookups each time. It is
  now memoised until a config reload (`ClientConfig.invalidatePalette`) or a
  different server palette arrives.
- **Chunk keys are built without a dimension string per call.** `location().toString()`
  allocated a String for every chunk drawn; the last dimension id is memoised in
  `ClientConcentration.dimensionIdOf`, which the region keys use too.
- **`chunkIsHighlit` no longer builds an observation.** Xaero asks it and
  `getColors` for the same chunk, so half of those calls now cost one map lookup
  and nothing else (`ClientConcentrationStore.hasValue`).
- **Band lookup no longer copies the enum array.** `values()` allocates a fresh
  array on every call; `ConcentrationClassification.byCode` reads a cached one.
- **Reads no longer mutate the cache.** The store was access-ordered, so every
  repaint rewrote eviction order under the lock the tick thread writes with. Recency
  now follows writes: the chunk still being confirmed keeps its slot, and what is on
  screen no longer decides what survives.
- **A tile rebuild no longer builds an observation to pick a colour.** `getColors`
  ran for every visible chunk on each map tile rebuild and went through
  `ClientConcentrationStore.observe`, paying an `Optional` for the entry, an
  `Observation` record and another `Optional` for the band - to colour a chunk, which
  needs only the band and whether the value is still current. `paintState` packs those
  two into one `int` from a single locked lookup (`NO_PAINT` when the chunk holds
  nothing), decoded with `bandOf`/`liveOf`; `observe` remains for the hover line, which
  does need the number.
- **The overlay colour lookup and its array allocate nothing.**
  `OverlayPalette.xaeroColorFor` built an `Optional` for the tier and a mapped
  `Optional` for the boxed colour; it now switches straight to the style. `getColors`
  also fills a per-thread `int[5]` instead of allocating one per chunk - safe because
  Xaero's `getChunkHighlitColor` copies those five entries into its own result store
  before returning, and per thread because the map's render and writer threads both
  ask.
- **The hover path allocates nothing per frame either.** `HoverText.describe` runs on
  every map frame and still went through `observe`, paying an `Optional` for the entry,
  an `Observation` and another `Optional` for the band on each frame. It now reads the
  immutable entry (`rawEntryOrNull`) and takes the number, band and liveness straight
  off it, so a hover frame allocates nothing at all once its line is remembered.
- **The hover line is built once, not once per frame.** `HoverText.describe` runs on
  every map frame for the block under the cursor, and each call ran a `String.format`
  for the number plus two `Component.translatable` nodes - the same three results
  while the pointer and the value held still. The line is now remembered and reused
  until the value, its liveness or its band changes (the chunk key is reused too),
  so a still hover only reads the store.
- **The mapped-chunk walks stop boxing every hit.** `XaeroWorldMapAccess` and
  `XaeroMinimapAccess` collected packed positions into a `HashSet<Long>`, so every
  mapped chunk in the window allocated a `Long` - the dominant cost of a walk that
  runs on the subscribe tick. Both now return a `LongSet` (`LongOpenHashSet`), as do
  the two bridges and `ClientConcentration.mappedChunksNearby`, so a hit is stored
  as the primitive it already is. Empty answers are the shared `LongSets.emptySet()`.
- **The world map's mapped test allocates nothing.** `WorldMapBridge.isChunkMapped`
  asked for a one-chunk square, which built a `HashSet`, packed a long and walked
  every loaded cave layer to answer a yes/no question. `XaeroWorldMapAccess.isMapped`
  now tests that chunk directly and stops at the first layer that has a tile; the
  layer walk's callback writes into one reusable probe instead of a fresh set.
- **Expiry sweeps only current values.** It used to copy the entire cache - up to
  `maxEntriesPerWorld`, 20 000 by default and 250 000 possible - into a snapshot
  every second, though only live values can expire and most of a long session's
  cache is remembered history. It now visits a live-key set (normally a few hundred)
  and allocates only for what actually expires.

### Fixed

- The cadence estimate and its last sample survived a server restart, so the first
  sweep of a second world in the same process was timed against the previous
  world's then widened the client's window for fifteen sweeps.
- **Logout no longer freezes behind the concentration save.** The final save was
  written inline on the client thread and then waited on for up to five seconds.
  It is queued like every other save, and the wait is bounded at 250 ms
  (`ConcentrationStorage.WRITE_TIMEOUT_MILLIS`); a write that misses the deadline
  finishes on the writer thread instead. The one place that genuinely needs the
  file current - a session change that reloads the world immediately - waits on
  its own write with the same bound.

## [0.1.0-alpha.11] — 2026-10-26

The request area is a circle instead of a square.

### Fixed

- **The client asked about chunks the server could never answer for.** The request
  radius was a square: its corners sit 14.1 chunks from the player at
  `radiusChunks = 10`, further than a server holds chunks loaded. Every corner chunk
  therefore came back as "no value" and showed as last known, which is one of the
  ways a normal, loaded area looked broken. `util/ChunkRadius` now measures in a
  straight line, and the server's `maxSubscribeRadiusChunks` cap and its pruning use
  the same predicate, so the two sides mean the same thing by "in range".
- Which also removes most of the traffic that produced the unreadable-chunk line:
  at radius 10 the request set drops from 441 chunks to 317.

### Changed

- The server's unreadable-chunk notice no longer blames a square radius that no
  longer exists: a chunk it cannot read is either outside its loaded area or a
  loaded chunk Tensura answered nothing for.

## [0.1.0-alpha.10] — 2026-10-26

The window follows the server through its own stalls, and an unreadable chunk now
says which kind of unreadable it was.

### Fixed

- **A stalling server still promised a healthy cadence.** The measured promise threw
  away every sample of 5 s or more, so a session logging `Can't keep up! ... 6330 ms
  or 126 ticks behind` kept promising 10 s per confirmation while a confirmation took
  far longer, and the client dimmed values the server was still sending. Stall
  samples are now what the promise is built from: the worst of the last 15 timed
  sweeps, extrapolated to one interval, floored at the nominal figure and capped at
  four intervals so a resumed pause cannot claim minutes. A recovering server
  narrows its promise again instead of carrying one stall for the session.
- The promise (and the baseline) is re-sent every 200 ticks instead of 600, so a
  server that starts stalling widens the client's window within one confirmation.

### Added

- `refresh/CadenceEstimate`, with its own unit tests, so the promise's bounds and
  its decay are testable without a game.
- The server now logs once per session why a subscribed chunk could not be read:
  no loaded chunk there (expected at the corners of a square request radius that
  reaches past the view distance) or a loaded chunk Tensura returned no storage or
  no usable value for. The client receives the same `NaN` either way, so this is
  the only place the two can be told apart.

## [0.1.0-alpha.9] — 2026-10-26

Values stop dimming while the game is paused, and the log now says which silence
actually demoted them.

### Fixed

- **Every value dimmed after a pause, then came back.** The freshness window is
  wall-clock time, but a singleplayer pause freezes the integrated server while the
  client keeps ticking, so a pause menu left open long enough expired the whole map
  and unpausing re-brightened it as heartbeats resumed. Time the server spent paused
  is now removed from the clock, so a pause cannot age a value.
- **Chunks the player was standing in stopped being confirmed.** The mapped set came
  from Xaero's *currently loaded* layers and regions, so an eviction made chunks that
  are still mapped disappear from the set: they were unsubscribed, demoted, and
  re-subscribed a second later, which is the dim-and-restore cycle itself. A chunk
  the maps have reported is now remembered for 60 s and stays subscribed while it is
  in range, and a confirmation for a still-subscribed chunk is accepted even if the
  map query momentarily says otherwise.
- **The diagnostic line reported the epoch as the gap.** `no confirmation for
  1793012145425 ms` was `now - 0`: the age was read after demotion replaced the
  entry, and a demoted entry carries no stamp. The gap is measured before demoting.

### Changed

- The expiry line now reports how many values went at once and how much of the
  session was paused, plus a second line for the first sweep that demotes eight or
  more. One chunk going quiet is a missed beat; a sweep that demotes dozens is the
  server having been unable to tick, and the log distinguishes them.

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

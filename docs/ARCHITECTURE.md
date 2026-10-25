# Architecture

How the mod is put together, and why each boundary sits where it does. The
authoritative facts about the third-party APIs are in
[`RECONNAISSANCE.md`](RECONNAISSANCE.md); this document is about our own code.

## 1. The two facts that drive the whole design

1. **Tensura's chunk magicule only exists on the logical server.** There is no
   S2C packet carrying it, the value is persisted in chunk NBT, and the client
   chunk storage is never populated. Anything a client draws therefore has to be
   transported by this mod.
2. **Only the client knows what is mapped.** Mapped state lives inside Xaero's
   in-memory map regions on the client. A server cannot evaluate it, so the
   client declares which chunks it has mapped and the server answers only those.

Everything else follows: per-player subscriptions, a server-side reader, and a
client cache whose ingress refuses unmapped chunks.

## 2. Side split

```
  server (logical)                                     client (logical)
  ─────────────────                                    ────────────────
  TensuraXaeroIntegration                              TensuraXaeroIntegration
    ├─ configs (common+server)                           ├─ configs (client)
    ├─ network registration                              ├─ network registration
    └─ server lifecycle                                  └─ client tick / payloads

  tensura/TensuraBridge        (no Tensura imports)      client/ClientConcentration
  tensura/TensuraAccess        (only Tensura importer)     ├─ ClientConcentrationStore
  tensura/TensuraTickObserver                              ├─ subscription diffing
        │ CHUNK_TICK_POST                                  └─ region invalidation
        ▼                                                client/ClientPayloadHandler
  server/ConcentrationServer                             client/WorldMapBridge
    ├─ per-player PlayerState                            client/MinimapBridge
    │    ├─ subscriptions: Set<ChunkCacheKey>                   │
    │    ├─ refresh/UpdateQueue                                 ▼
    │    └─ refresh/ReconcileCursor                      client/XaeroWorldMapAccess
    ├─ onSubscribe (C2S validation)                      client/XaeroMinimapAccess
    └─ onServerTick (reconcile + flush)                  client/overlay/*Highlighter
        │                                                       ▲
        │ ConcentrationPayload (S2C, batched)                  │
        └──────────────────────────────────────────────►  mixin: HighlighterRegistry.end()
```

`client/Xaero*Access` are the **only** classes that import Xaero types;
`tensura/TensuraAccess` is the **only** class that imports Tensura types. Every
caller goes through a bridge that checks `ModList` first, so a missing optional
mod never causes a `NoClassDefFoundError` — the JVM never resolves a reference in
a class that is not loaded.

## 3. Server components

### `tensura/TensuraBridge`
Side-effect-free facade used by everything else on the server. `isLoaded()`
checks `ModList` for `tensura`; `baseline()`, `baselineAt()`,
`concentrationAt(level, chunk)` and `available()` catch `Throwable` and degrade to
"no data". The bridge deliberately contains no Tensura import, so it is safe to
load in a pack without Tensura.

### `tensura/TensuraAccess`
The single Tensura-aware class. Reads `ChunkStorage.getMagicule()` through
`TensuraStorages.getChunkFrom(chunk)` behind a `level.hasChunk(...)` guard.
`AreaMagiculeHelper.getMagicule(Level, BlockPos)` is **not** used for chunk
queries because it substitutes `baseMagicule` when the chunk is not loaded,
which would turn "no data" into a plausible-looking fake value. A chunk that is
not loaded is reported unavailable; nothing is ever loaded or generated to
answer a query.

### `tensura/TensuraTickObserver`
Registers a listener on Tensura's `TensuraLevelEvents.CHUNK_TICK_POST` (an
Architectury event), guarded by `ModList`. It is registered once and forwards
`(ServerLevel, LevelChunk)` to `ConcentrationServer.onChunkTicked`. This is the
event-driven half of the hybrid refresh: a *tick* notification per loaded chunk,
not a value-change notification, so the coordinator still diffs the value before
sending anything.

### `server/ConcentrationServer`
Server singleton, started on `ServerStartedEvent` and stopped on
`ServerStoppingEvent`. Holds one `PlayerState` per online player:

| Field | Purpose |
| --- | --- |
| `subscriptions` | the `ChunkCacheKey` set this player is allowed to receive |
| `queue` | `UpdateQueue` — coalescing with a per-chunk minimum interval |
| `cursor` | `ReconcileCursor` — rotates a bounded slice over the subscription set |
| `receivedSubscribe`, `lastSubscribeTick` | subscription packet rate limiting |
| `baselinePending` | whether the next tick must push the baseline |

Responsibilities:

- **Validation.** `onSubscribe` rejects a packet when sync is off, the player is
  unknown, the dimension is not the one the player is standing in, the packet
  arrived inside `minSubscribePacketIntervalTicks`, a chunk is outside the
  configured radius, or the player's cap is reached. The client's declaration is
  trusted for *which* chunks it has mapped, never for whether a value may be sent.
- **Event path.** `onChunkTicked` looks up the key, returns immediately unless
  some player subscribed to that chunk, and then offers the current value to each
  such player's queue. No packet is built per chunk; the queue is flushed once
  per tick.
- **Periodic path.** `onServerTick` pushes the baseline when it is due
  (600 ticks) or pending, prunes subscriptions that left the player's dimension
  or radius, runs `reconcile` every `reconcileIntervalTicks`, and flushes.
- **Bounded work.** `reconcile` re-reads at most `reconcileBudgetChunksPerPlayer`
  entries per cycle from a rotating cursor, so a player with 2 000 subscriptions
  costs a few hundred field reads every few seconds rather than a stall.
- **Unloaded chunks.** `offerCurrent` reports a chunk that is no longer loaded as
  unavailable (`NaN`) instead of letting its last value live on.
- **Privacy.** Nothing is ever sent for a chunk the player did not subscribe to,
  and subscriptions are additionally bounded by distance and loaded state, so a
  modified client can only reach chunks it is physically standing among.

### `net/*`
- `SubscriptionPayload` (C2S): dimension id, `subscribe` flag, `long[]` packed
  chunk positions. Packets are capped at `NetworkLimits.MAX_SUBSCRIBE_CHUNKS`.
- `ConcentrationPayload` (S2C): dimension id, `long[]` positions, `double[]`
  values, `NaN` meaning "no authoritative value right now".
- `BaselinePayload` (S2C): the server's configured baseline, so clients do not
  have to guess it.
- `PackedChunkPos`: `(x << 32) | (z & 0xFFFFFFFFL)` with sign-safe unpacking.

## 4. Client components

### `client/ClientConcentration`
The session owner. On every client tick it:

1. detects a world or dimension change and starts a new session (clearing the
   store, releasing the old dimension's subscriptions);
2. ticks the store, and every 20 ticks expires stale entries and purges old ones;
3. re-declares its mapped set every `subscribeIntervalTicks` (and every 200 ticks
   re-sends the whole set, which is idempotent and self-healing against a server
   radius cap smaller than the client's);
4. invalidates the map regions whose values changed.

The mapped set is built from `WorldMapBridge.mappedChunks(...)` and
`MinimapBridge.mappedChunks(...)` — the maps' own mapped state, intersected with
the configured radius around the player. A chunk that is not mapped is never
requested.

### `client/ClientConcentrationStore`
Pure, Minecraft-free store (`ChunkCacheKey` → entry). Three rules:

- `offer(key, mapped, value)` **refuses the write when `mapped` is false**, so an
  unmapped chunk can never enter the cache even if a packet named it.
- Entries carry both the value and a "last refreshed" tick; `expire()` marks
  values past `staleAfterTicks` as stale (they stop painting and the hover line
  says unavailable instead of showing a number), `purge()` drops them entirely.
- Every accepted change bumps that chunk's region version, which is what the
  overlay's region hash reads.

`markUnavailable(key, mapped)` records "authoritative answer is missing" without
inventing a value; the entry renders as nothing and reads as unavailable.

### `client/ClientPayloadHandler`
Dispatches `ConcentrationPayload` and `BaselinePayload` on the client thread via
`enqueueWork`, then hands them to `ClientConcentration`. No world access happens
on the network thread.

### `client/WorldMapBridge` / `client/MinimapBridge`
`ModList`-gated, Xaero-free facades: `available()`, `isChunkMapped(...)`,
`mappedChunks(dimension, cx, cz, radius)`, `refreshRegion(RegionKey)`. They call
into `XaeroWorldMapAccess` / `XaeroMinimapAccess` only after the gate.

### `client/XaeroWorldMapAccess` / `client/XaeroMinimapAccess`
The Xaero-aware classes:

- mapped test: `MapProcessor.getMapTile(...)` / `MapTileChunk.getTile(...)` for
  the world map, `MinimapWriter.getLoadedBlocks()` + `MinimapChunk.getTile(...)`
  for the minimap — i.e. "a tile actually exists for this chunk", which is what
  Xaero itself means by mapped. Chunk-loaded, chunk-generated and
  server-visited are *not* substitutes.
- repaint: `DimensionHighlighterHandler.clearCachedHash(regionX, regionZ)` on the
  world map and `requestRefresh(regionX, regionZ)` on the minimap writer, both
  guarded to the dimension currently being viewed.

### `client/overlay/*`
`WorldMapConcentrationOverlay` and `MinimapConcentrationOverlay` implement each
map's own `ChunkHighlighter` (the two interfaces are distinct classes in the two
mods and are not interchangeable). They share `concentration/` for classification
and colour, so the two maps can never disagree:

- `chunkIsHighlit` — true only for a chunk present in the store, mapped, fresh,
  and above the baseline band; this gate also controls whether the tooltip path
  is consulted at all.
- `getChunkHighlitColor` — 256 ARGB entries in Xaero's channel order
  (`blue << 24 | green << 16 | red << 8 | alpha`), with a zero-alpha colour for
  chunks that are highlit only so their tooltip can show.
- `calculateRegionHash` / `regionHasHighlights` — derived from the store's
  per-region version, so a region repaints exactly when one of its values changed.
- `getBlockHighlightSubtleTooltip` / `getBlockHighlightBluntTooltip` (world map
  only) — the hovered block's chunk, never the player's chunk, rendered through
  `client/HoverText` and `util/ConcentrationText`.

Constructors pass `coveringOutsideDiscovered = false`, so Xaero's own discovered
gate is an additional outer barrier for anything this code might miss.

### `mixin/*`
Each map's `HighlighterRegistry` is populated during session init and then frozen
by `end()`, and neither mod publishes a registration call for third-party mods.
Two client-only mixins inject at `HEAD` of `end()` — the last moment the list is
still mutable — and register that map's overlay. Each mixin config declares
`requiredMods` for exactly one map mod, so the mixin is never applied when that
map is absent, and each lives in its own config file so a minimap-only or
world-map-only install loads only what exists.

## 5. Pure core (unit-tested, no Minecraft imports)

| Class | Responsibility |
| --- | --- |
| `cache/ChunkCacheKey` | world token + dimension + chunk x/z; the only identity used for data |
| `cache/ConcentrationEntry` | value, mapped flag, timestamps, staleness |
| `cache/ConcentrationCache` | bounded map with LRU-ish eviction and invalidation |
| `cache/RegionKey` | 32×32-chunk region identity used for repaint invalidation |
| `concentration/ConcentrationThresholds` | baseline-relative band boundaries, validated to increase |
| `concentration/ConcentrationClassification` | the five presentation bands |
| `concentration/OverlayPalette` | `#RRGGBB` parsing, per-band ARGB in Xaero's encoding |
| `refresh/UpdateQueue` | change detection, per-chunk minimum interval, bounded drain, retention pruning |
| `refresh/ReconcileCursor` | rotating bounded slice over a set |
| `client/ClientConcentrationStore` | mapped-only ingress, staleness, region versions |

These carry the logic that the specification cares about, which is why the JUnit
suite can cover it without a game client.

## 6. Refresh, end to end

```
block/entity/ability changes magicule
        │
ChunkStorage.tick() pulls the value toward max at the regeneration rate
        │
TensuraLevelEvents.CHUNK_TICK_POST (server thread, loaded chunks only)
        │
TensuraTickObserver → ConcentrationServer.onChunkTicked
        │  key must be subscribed by some player
        ▼
UpdateQueue.offerIfChanged(key, value, tick)        ← drops no-op updates
        │  held until minUpdateIntervalTicks elapsed
        ▼
flush() batches up to maxUpdatesPerPacket → ConcentrationPayload
        ▼
ClientPayloadHandler → ClientConcentration.accept → store.offer(mapped only)
        │  changed value bumps the region version
        ▼
ClientConcentration.refreshDirtyRegions → WorldMapBridge/MinimapBridge.refreshRegion
        ▼
Xaero recomputes the region highlight hash → repaint
```

Periodic reconciliation runs the same offer path from `onServerTick`, reading a
rotating slice of the player's subscriptions. It exists because
`CHUNK_TICK_POST` only fires for ticking, loaded chunks: a chunk that unloads, or
one whose value changed while no observer ran, would otherwise keep a stale
value forever.

## 7. Failure behaviour

| Situation | Behaviour |
| --- | --- |
| Tensura absent | bridge gate false; coordinator sends nothing; no Tensura class loaded |
| Xaero mod absent | that bridge reports unavailable, its mixin config never applies, other features unaffected |
| Neither present | mod loads, both configs register, nothing is drawn or sent |
| Chunk unloaded | reported unavailable (`NaN`); client stops drawing and says unavailable |
| Packet names an unmapped chunk | store refuses the write |
| Dimension change | old subscriptions released, store cleared, baseline kept |
| World change / disconnect | full session reset, including baseline |
| Value unchanged | no packet, no repaint |
| Config threshold order invalid | falls back to defaults with one warning |

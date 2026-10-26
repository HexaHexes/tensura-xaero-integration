# Reconnaissance report

Everything below was read out of the actual artifacts on this machine: the pinned
Tensura: Reincarnated jar, the pinned Xaero jars, the NeoForge template, and the
existing HexaHexes mod repositories. Decompiled with CFR 0.152 and inspected with
`javap`; nothing here is inferred from a similarly named mod or from memory.

Every claim is marked **(verified)** when it came from a class file or source in
this environment, or **(open)** when it could not be settled here.

---

## 1. Verified environment

| Item | Value | Evidence |
| --- | --- | --- |
| OS / shell | Windows, bash (Git Bash) | session |
| JDK | 21.0.9 LTS, `C:\Program Files\Java\jdk-21` (`JAVA_HOME` unset; must be exported per shell) | `java -version` |
| Gradle | wrapper 8.12.1 (bin) | `gradle/wrapper/gradle-wrapper.properties` |
| NeoForge moddev plugin | `2.0.148` | template `build.gradle` |
| Minecraft | 1.21.1 | template `gradle.properties` |
| NeoForge | 21.1.259 | template `gradle.properties` |
| Local mod root | `C:\Games\Minecraft\Mods` | directory listing |
| Local repos | `neoforge-mod-template`, `lightmans-tensura`, `tensura-jade`, `tensura-apotheosis`, `tensura-faction-spawn`, `pseudocore`, `infinite-dungeon`, `infinite-dungeon-api`, `infinite-dungeon-spawn-safety`, `tiered-echoes`, `enchantment-packs`, `potion-packs` | directory listing |
| GitHub auth | `gh` logged in as `YaibaToKen`, scopes include `repo`, `workflow`; SSH remotes | `gh auth status` |
| Decompiler used for inspection | CFR 0.152 (scratch download, not committed) | — |

### Org conventions extracted from existing repos (verified)

- Branch `main`; SSH remote `git@github.com:HexaHexes/<repo>.git`; PRs squash-merge.
- `mod_id` is lowercase-with-underscores; repo/folder lowercase-hyphenated.
- Java base package is `org.bitwisemadness.<repo-name with separators stripped>`.
- Dependencies resolve through Maven only — CurseMaven (`https://cursemaven.com`)
  or Modrinth's Maven — never gitignored `libs/`.
- `LICENSE` is LGPL v2.1 (template ships it and the NOTICE/attribution rule).
- CI is three checks: `junit` (non-vacuous), `gametest` (graded on the
  `All N required tests passed` log line), `version-bump`.
- `agents/AGENTS.md` carries the branch/verification/commit rules.
- GameTests live in a `gametest` source set that is joined to dev runs only.

The local template checkout is on branch `ci/three-checks-junit-gametest`, which is
newer than local `main` and matches every derived repo (three-check CI, CurseMaven
dependency rule). This project was seeded from that working tree.

---

## 2. Tensura: Reincarnated

### 2.1 Artifact and identity

| Item | Value |
| --- | --- |
| Jar used for inspection | `tensura-neoforge-2.0.1.3.jar` (local copy) |
| CurseMaven coordinate (org standard) | `curse.maven:tensura-reincarnated-643695:8793360` |
| `modId` | `tensura` |
| Version | `2.0.1.3` |
| License | **All Rights Reserved** → the jar must never be committed or redistributed |
| Root package | `io.github.manasmods.tensura` |
| Hard dependencies | `manascore_storage`, `manascore_skill`, `manascore_race`, `architectury`, `geckolib` (declared in its `neoforge.mods.toml`) |
| Client/proxy layout | Architectury (nested common jar) |

There is **no separate API artifact** published for Tensura. Addon mods in this org
(`tensura-jade`, `tensura-apotheosis`, `tensura-faction-spawn`) compile against the
mod jar itself with `compileOnly`. The public surface of that jar is the API.

### 2.2 Chunk magicule concentration — the authoritative source **(verified)**

```
io.github.manasmods.tensura.storage.chunk.ChunkStorage
  extends io.github.manasmods.manascore.storage.api.Storage
  implements io.github.manasmods.tensura.storage.chunk.IChunk

  public static final io.github.manasmods.tensura.config.AreaMagiculeConfig CONFIG;
  public static io.github.manasmods.manascore.storage.api.StorageKey<ChunkStorage> getKey();
  public static net.minecraft.core.BlockPos getMiddlePos(net.minecraft.world.level.chunk.LevelChunk);

  public double getMagicule();                       // authoritative ambient value
  public void   setMagicule(double);
  public double getMaxMagicule();
  public double getRegenerationRate();
  public java.util.List<BlockMagiculeModifier> getBlockModifiers();
  public void tick();
```

- The storage is registered by `ChunkStorage.init()` through
  `StorageEvents.REGISTER_CHUNK_STORAGE` under the id `tensura:chunk_storage`, for
  **every** `LevelChunk` (`levelChunk -> true`), and is attached to the chunk by
  ManasCore's `StorageHolder` interface (`chunk.manasCore$getStorage(key)`).
- It is **persisted in chunk NBT** (`save`/`load` write `magicule`, `maxMagicule`,
  `regenerationRate`, `magiculeSeed`, `blockModifiers`).
- Values are **dynamic**: `tick()` (called from `TensuraLevelEvents.CHUNK_TICK_POST`,
  registered by Tensura itself) pulls `magicule` toward `maxMagicule` at
  `getRegenerationRate()` per chunk tick. Block modifiers (e.g. magic engines),
  abilities consuming magicule (`consumeMagicule`), and config seed changes all move
  the value.
- `getMagicule()` returns the chunk-level value; per-block modifiers are applied only
  by `AreaMagiculeHelper.getMagicule(entity, true)` for a specific position. For a
  map overlay the chunk-level value is the right quantity.

**Supported accessor used by Tensura itself** (verified, public, and the one this mod uses):

```
io.github.manasmods.tensura.storage.AreaMagiculeHelper   // public final class
  public static double getMagicule(net.minecraft.world.level.Level, net.minecraft.core.BlockPos);
  public static double getMagicule(net.minecraft.world.entity.LivingEntity);
  public static double getMagicule(LivingEntity, boolean countBlockModifier);
  public static double getMaxMagicule(Level, BlockPos);
  public static com.mojang.datafixers.util.Pair<Double,Double> getMagiculePair(Level, BlockPos);
  public static boolean consumeMagicule(Level, BlockPos, double);
  ...
io.github.manasmods.tensura.storage.TensuraStorages
  public static ChunkStorage getChunkFrom(net.minecraft.world.level.chunk.LevelChunk);
```

`AreaMagiculeHelper.getMagicule(Level, BlockPos)`:

1. computes the chunk from the block position,
2. **returns `ChunkStorage.CONFIG.baseMagicule` when `!level.hasChunk(...)`** — a
   baseline placeholder, *not* an authoritative value,
3. otherwise `TensuraStorages.getChunkFrom(level.getChunk(...)).getMagicule()`.

This mod therefore calls `TensuraStorages.getChunkFrom` behind its own
`level.hasChunk(...)` guard, so a missing/unloaded chunk is reported as *unavailable*
instead of being silently replaced by the baseline. No chunk is ever loaded or
generated to answer a query.

### 2.3 Baseline and units **(verified)**

```
io.github.manasmods.tensura.config.AreaMagiculeConfig extends ManasConfig
  public double baseMagicule = 500.0;             // "Base magicule value for a chunk."
  public double maximumMagicule = 1000000.0;
  public double baseMagiculeRegeneration = 10.0;
  public int    modifierUpdateInterval = 200;
  public long   magiculeSeed = <random>;
  public double minimalMagiculeSpawn = 10.0;
  ...
```

- The **normal baseline is explicit**: `ChunkStorage.CONFIG.baseMagicule`
  (default `500.0`). Zero is *not* the baseline.
- Range: `[0, maximumMagicule]`, where `maximumMagicule` is the per-chunk
  biome/level-modified maximum (up to the configured `maximumMagicule`). Tensura has
  **no** named concentration classes/tiers; any classification in this mod is a
  presentation concept by definition.
- Unit: plain config units of "magicule" (no name/suffix attached).

### 2.3.1 What actually limits a chunk's value **(verified)**

A chunk's stored maximum starts at `baseMagicule` and is then modified by the
`tensura:biome_magicule` entry for the biome at the chunk's middle position and
the `tensura:level_magicule` entry for the dimension (`ChunkStorage#getMaxMagicule`
→ `MagiculeCacheEntry#getMaxMagicule`, modifiers applied in priority order).
Tensura's `tick()` then moves the value to that maximum in one step, so a loaded
chunk reads its computed maximum almost immediately.

The shipped data files decide the ceilings:

| Modifier file | Effect |
| --- | --- |
| `minecraft:level_magicule/overworld` | **no modifiers at all** - the overworld level contributes nothing |
| `minecraft:biome_magicule/dark_forest` | `ADD 1500` |
| `minecraft:biome_magicule/deep_dark` | `ADD 2000` |
| `minecraft:biome_magicule/basalt_deltas` | `ADD 2500` |
| `minecraft:biome_magicule/crimson_forest`, `end_*` | `ADD 4500` |
| `minecraft:biome_magicule/warped_forest` | `ADD 3500` |
| every other overworld biome | no entry |
| `tensura:level_magicule/hell` | `ADD 100000` |
| `tensura:biome_magicule/ancient_forest`, `miasmic_plains` | `ADD 19500` |
| `tensura:biome_magicule/barren_land` | `ADD 45500` |

With the shipped `baseMagicule = 500` that means ordinary overworld biomes sit at
exactly **500**, dark forest at **2000**, deep dark at **2500**; nothing in the
overworld can reach the configured `maximumMagicule` of 1,000,000, and a magic
engine nearby (`magicEngineReduction = 1000`) subtracts further. Any world where
the overworld tops out at 1500 has a `baseMagicule` of 0 (1500 is precisely the
dark-forest addend), or a pack-level override of these files.

### 2.4 Missing data **(verified)**

- `TensuraStorages.getChunkFrom` returns `null` when the chunk has no registered
  storage (`manasCore$getStorage` miss) — a distinct state from a valid `0.0`.
- A chunk that is not loaded has no `ChunkStorage` instance at all.
- Client-side `ChunkStorage.isInitialized()` returns `true` unconditionally, and a
  client chunk never receives the server's stored values, so **client-side reads are
  not authoritative**.

### 2.5 Sidedness and synchronisation **(verified)**

- There is **no S2C packet carrying chunk magicule**. The `io.github.manasmods.tensura.network`
  tree contains ability/naming/menu payloads only; no client class reads chunk
  magicule. The value therefore exists on the logical server only.
- The storage is registered for `LevelChunk` on both sides (ManasCore's holder is a
  common mixin), but a client chunk's storage is uninitialised and never synced.
- **Consequence:** an integration that shows authoritative values on a client map must
  transport them itself, from the server, with its own visibility rules. Tensura
  provides no client-visible value to piggyback on, so skipping networking is not an
  option.

### 2.6 Change notification **(verified)**

```
io.github.manasmods.tensura.event.TensuraLevelEvents
  public static dev.architectury.event.Event<Runnable> POST_INIT;
  public static dev.architectury.event.Event<ChunkTickEvent> CHUNK_TICK_PRE;
  public static dev.architectury.event.Event<ChunkTickEvent> CHUNK_TICK_POST;
  public static dev.architectury.event.Event<LevelPreparedEvent> LEVEL_PREPARED;
  interface ChunkTickEvent { void tick(ServerLevel level, LevelChunk chunk); }
```

- `CHUNK_TICK_POST` is fired from `MixinServerLevel#onPostTickChunk` at `RETURN` of
  `ServerLevel.tickChunk(LevelChunk, int)` — server thread, after Tensura's own
  `ChunkStorage.tick()` listener (registered first during Tensura init).
- It is a **tick notification per chunk**, not a "value changed" notification: it
  fires for every ticking chunk every tick whether or not the value moved.
- There is no event that carries a new concentration value, and no callback on
  `setMagicule`/`consumeMagicule`.
- `dev.architectury.event.Event` exposes `register`/`unregister`/`isRegistered`
  (verified with `javap`), so a listener can be added and is safely idempotent if
  guarded.

**Consequence:** the event-driven half of the hybrid refresh is "observe the chunk
tick, then diff the authoritative value", not "consume a change event". Periodic
reconciliation remains mandatory, both as a safety net and for chunks whose value
changed without a tick observer attached.

### 2.7 Capabilities not present **(verified)**

- No per-chunk value for unloaded chunks (no world-save read API; values live in
  chunk NBT behind `Storage`).
- No API to ask "is this chunk mapped/synced".
- No public API to iterate all loaded chunks' concentrations other than
  `ServerLevel.getChunkSource()` / `ChunkMap` iteration (vanilla), which this mod uses
  only to walk *subscribed* chunks, never to load any.

---

## 3. Xaero's Minimap and Xaero's World Map

### 3.1 Artifacts and identity **(verified)**

| Item | Minimap | World Map |
| --- | --- | --- |
| Jar inspected | `xaerominimap-neoforge-1.21.1-26.6.0.jar` | `xaeroworldmap-neoforge-1.21.1-1.47.0.jar` |
| `modId` | `xaerominimap` | `xaeroworldmap` |
| Version | 26.6.0 | 1.47.0 |
| License | All Rights Reserved (never commit the jar) | All Rights Reserved |
| CurseMaven coordinate | `curse.maven:xaeros-minimap-263420:9067173` | `curse.maven:xaeros-world-map-317780:9067277` |
| Class count | 692 | 373 |
| Declared relationship | World Map lists `xaerominimap` as an **optional** dependency, `[26.4.0,)` | — |

There is **no API jar** for either mod and no documented, supported overlay API in the
published downloads. The public classes of the jars are the only interface.

### 3.2 Chunk overlay mechanism — highlighters **(verified)**

Both mods expose a chunk-colour overlay interface. The two copies are **separate
classes** and are not interchangeable:

```
xaero.map.highlight.AbstractHighlighter           (World Map)
xaero.map.highlight.ChunkHighlighter              (World Map, 5-colour chunk fill helper)
  protected ChunkHighlighter(boolean coveringOutsideDiscovered)
  public abstract int calculateRegionHash(ResourceKey<Level>, int regionX, int regionZ)
  public abstract boolean regionHasHighlights(ResourceKey<Level>, int regionX, int regionZ)
  public abstract boolean chunkIsHighlit(ResourceKey<Level>, int chunkX, int chunkZ)
  public int[] getChunkHighlitColor(ResourceKey<Level>, int chunkX, int chunkZ)   // 256 ARGB values
  public Component getBlockHighlightSubtleTooltip(ResourceKey<Level>, int blockX, int blockZ)
  public Component getBlockHighlightBluntTooltip(ResourceKey<Level>, int blockX, int blockZ)
  public abstract void addMinimapBlockHighlightTooltips(List<Component>, ResourceKey<Level>, int, int, int)

xaero.common.minimap.highlight.AbstractHighlighter (Minimap)
xaero.common.minimap.highlight.ChunkHighlighter   (Minimap)
  ... same shape, plus
  public abstract void addChunkHighlightTooltips(InfoDisplayCompiler, ResourceKey<Level>, int, int, int)
```

- Colour encoding (verified in `DimensionHighlighterHandler.applyChunkHighlightColors`):
  `int c` → alpha `c & 0xFF`, red `c >> 8 & 0xFF`, green `c >> 16 & 0xFF`, blue
  `c >> 24 & 0xFF`, i.e. `blue << 24 | green << 16 | red << 8 | alpha`.
- `coveringOutsideDiscovered` (constructor flag) decides whether a highlighter paints
  chunks that were never mapped. Both maps gate every call on a `discovered` boolean:

  ```
  // xaero.map.highlight.DimensionHighlighterHandler
  if (!discovered && !hl.isCoveringOutsideDiscovered()) return false;
  ```

  With `coveringOutsideDiscovered = false`, Xaero itself never renders or tooltips the
  highlighter's output for undiscovered chunks. That is the outer privacy gate; this
  mod adds its own mapped-only data rules on top.
- Region maths (verified): `MapRegion` = 32×32 chunks, `MapTileChunk` = 4×4 chunks,
  `MapTile` = 1 chunk; `MapTileChunk.getTile(x&3, z&3) != null` ⇔ that chunk has been
  written (mapped). `MapRegion.hasHadTerrain()` is the coarse variant.
- `MinimapChunk` = 4×4 chunks (`SIZE_TILES = 4`), `MinimapChunk.getTile(x&3, z&3) != null`
  ⇔ mapped on the minimap.

### 3.3 Highlighter registration — the one real gap **(verified)**

World Map:

```
xaero.map.WorldMapSession.init(...)
  HighlighterRegistry highlightRegistry = new HighlighterRegistry();
  if (SupportMods.pac()) SupportMods.xaeroPac.registerHighlighters(highlightRegistry);
  highlightRegistry.end();                       // <- makes the list unmodifiable
  this.mapProcessor = new MapProcessor(..., highlightRegistry, ...);
```

- `WorldMapSession.getCurrentSession()` (public static) → `getMapProcessor()` (public)
  → `getHighlighterRegistry()` (public) **exists**, but `HighlighterRegistry.register`
  after `end()` throws `UnsupportedOperationException` (list replaced by
  `Collections.unmodifiableList`). A post-session registration is impossible.
- `xaero.map.mods.SupportMods` is Xaero's own internal integrations class (fields for
  PAC, Iris, …), not a third-party registration point.
- There is no NeoForge event for highlighter registration (`ModClientEventsNeoForge`
  has only texture atlas, reload listeners, GUI layers, key mappings).

Minimap:

- `MinimapSession` (`xaero.hud.minimap.module.MinimapSession`) creates its own private
  `HighlighterRegistry`, calls `end()`, and passes it to `MinimapWriter` (private final
  field, **no getter**).
- `xaero.map.mods.SupportXaeroMinimap.registerMinimapHighlighters(Object)` — the hook
  that would have forwarded world-map highlighters into the minimap — is **an empty
  method body** in 1.21.1 (verified in source). Minimap highlights are not propagated
  from the World Map registry.

**Conclusion:** a chunk overlay can be supplied through a supported *callback
interface*, but neither mod exposes a supported *registration* call for third-party
mods in the pinned versions. Registering therefore needs one minimal mixin per map,
injected at `HighlighterRegistry.end()` (HEAD), i.e. the single point where the list
is still mutable and immediately before it is frozen:

```java
@Mixin(HighlighterRegistry.class)                      // world map copy
class WorldMapHighlighterRegistryMixin {
  @Inject(method = "end", at = @At("HEAD"))
  private void txi$register(CallbackInfo ci) { this.register(WorldMapOverlay.get()); }
}
```

This is the least invasive option that preserves the required functionality; the
alternatives (reflection into a private field, or rewriting the map's own code) are
strictly worse. Each mixin config is client-only and carries `requiredMods` for its
own map mod, so it can never apply when that map is absent.

### 3.4 Overlay redraw **(verified)**

World Map:

- Every GUI frame compares the region's prepared highlight hash with a fresh one:
  `region.getHighlightsHash() != dim.getHighlightHandler().getRegionHash(rx, rz)`
  → region is queued for reload/repaint.
- `MapDimension.getHighlightHandler()` is public; `DimensionHighlighterHandler` exposes
  `getRegionHash(rx, rz)` and `clearCachedHash(rx, rz)` (which also clears the region
  highlight-existence tracker and notifies the minimap).
- `AbstractHighlighter.calculateRegionHash` is folded into that cached hash. A
  highlighter that returns a constant can therefore never trigger a repaint; returning
  a per-region version that changes with the data is what makes updates appear.

Minimap:

- `MinimapWriter.getDimensionHighlightHandler()` is public and
  `DimensionHighlighterHandler.requestRefresh(regionX, regionZ)` bumps the writer's
  highlight version when the region is inside the loading canvas (and
  `requestRefresh()` for the whole canvas). That is the supported redraw trigger.

### 3.5 Hover integration **(verified)**

`xaero.map.gui.GuiMap` renders, for the block under the cursor
(`this.mouseBlockPosX/Z`, not the player position):

```java
discoveredForHighlights = mouseBlockBottomY != Short.MAX_VALUE;
highlightHandler.getBlockHighlightSubtleTooltip(mouseBlockPosX, mouseBlockPosZ, discoveredForHighlights)
highlightHandler.getBlockHighlightBluntTooltip(mouseBlockPosX, mouseBlockPosZ, discoveredForHighlights)
```

- The **subtle** line is drawn as a centred line at the bottom of the screen; the
  **blunt** line is drawn as a tooltip box at the cursor. Both are concatenated across
  registered highlighters by `DimensionHighlighterHandler.getBlockHighlightTooltip`.
- Both are routed through `ChunkHighlighter`, which returns `null` unless
  `chunkIsHighlit(dimension, blockX >> 4, blockZ >> 4)`. So a chunk that must show a
  tooltip has to report itself as highlit; a zero-alpha colour keeps the overlay
  invisible while still allowing the tooltip.
- No map-modification is needed for hover: the highlighter interface already receives
  the hovered chunk's coordinates and the discovered flag.

### 3.6 Mapped-chunk visibility for data management **(verified)**

- World Map: `MapProcessor.getMapTile(caveLayer, chunkX, chunkZ)` (public) returns the
  `MapTile` for a chunk in the currently viewed dimension, or `null`. Any loaded layer
  is reachable through `MapWorld.getDimension(key)` →
  `MapDimension.getLayeredMapRegions()` → `applyToEachLoadedLayer((layer, mapLayer) -> …)`
  → `MapLayer.getMapRegions().getLeaf(regionX, regionZ)` → `MapRegion.getChunk(x&7, z&7)`
  → `MapTileChunk.getTile(x&3, z&3)`.
- Minimap: `MinimapProcessor.getMinimapWriter()` (public) →
  `MinimapWriter.getLoadedBlocks()` (public) → `MinimapChunk[][]` for the current
  window, with `getLoadedMapChunkX/Z()` as the origin.
- Both are "currently in memory" views: a region that is not loaded in memory cannot be
  queried here, and its chunks are treated as not mapped. For data purposes that is
  correct — those chunks are exactly the ones this client is not viewing.

### 3.7 Distribution constraints **(verified)**

Both Xaero jars and the Tensura jar are All Rights Reserved. They are consumed as
`compileOnly`/runtime-only CurseMaven coordinates and are never committed to the repo
or bundled into the produced jar.

---

## 4. Compatibility matrix

| Combination | Result | Mechanism |
| --- | --- | --- |
| Tensura + Minimap + World Map | Minimap overlay, world-map overlay, world-map hover | both mixins + both bridges |
| Tensura + Minimap only | Minimap overlay | world-map bridge never loaded; world-map mixin config skipped via `requiredMods` |
| Tensura + World Map only | World-map overlay + hover | minimap bridge never loaded; minimap mixin config skipped |
| Tensura, no Xaero | No map feature, no crash | client bridges short-circuit on `ModList`; no Xaero class is touched |
| Xaero, no Tensura | No concentration feature, no crash | server coordinator disabled; client receives no baseline |
| Neither | Plain no-op mod | all integration code is gated |

Client-only mods (Xaero) are never loaded on a dedicated server: every class that
imports them lives behind `Dist.CLIENT` entry points and a `ModList` gate.

---

## 5. Recommended architecture

```
server (logical)                                  client (logical)
┌────────────────────────────────────┐            ┌─────────────────────────────────┐
│ TensuraAccess                      │            │ ClientConcentrationStore (pure) │
│  hasChunk guard + getChunkFrom     │            │  mapped-only ingress, TTL,      │
│ TensuraTickObserver                │            │  region versions, session token │
│  CHUNK_TICK_POST → value diff      │  S2C       │ WorldMapBridge                  │
│ ConcentrationServer                │ ─────────► │  mapped check, refresh, hover   │
│  per-player subscriptions,         │  update    │ MinimapBridge                   │
│  radius/loaded validation,         │  batches   │  mapped check, refresh          │
│  reconcile budget, coalescing      │ ◄───────── │ ClientConcentration (driver)    │
└────────────────────────────────────┘  C2S sub   └─────────────────────────────────┘

Pure core (no Minecraft imports, JUnit-tested):
  ChunkCacheKey, ConcentrationEntry, ConcentrationCache, RegionKey,
  ConcentrationClassification, ConcentrationThresholds, OverlayPalette,
  UpdateQueue, ReconcileCursor, ClientConcentrationStore
```

Key boundaries:

- **Only the server reads Tensura.** `TensuraAccess` / `TensuraBridge` live
  server-side; the client never imports Tensura.
- **Only the client knows mapped state.** The server cannot evaluate Xaero data, so
  the client declares its mapped chunks by subscribing; the server validates
  everything it *can* (subscription membership, chunk loaded, distance from the
  player) and never broadcasts unrequested chunks.
- **No concentration logic is reimplemented.** The mod only reads
  `ChunkStorage.getMagicule()` and the configured baseline.
- **Rendering never performs I/O or world access.** Highlighters read the client cache
  only.

---

## 6. Risks and fallbacks

| Risk | Mitigation |
| --- | --- |
| Xaero class shapes are internal, not contractual | pinned CurseMaven coordinates, per-map `requiredMods`, no refmap-dependent member names beyond the pinned version; documented as a version-sensitive integration |
| A future Xaero release moves `HighlighterRegistry.end()` | mixin fails loudly at load with the map mod present; documented upgrade note in `docs/COMPATIBILITY.md` |
| Tensura renames `getMagicule`/`getChunkFrom` | compile-time pin + runtime `try/catch` in the bridge degrades to "no data" instead of crashing |
| Authoritative values only exist while the chunk is loaded | explicitly designed for; unloaded chunks are `unavailable`, never approximated with the baseline |
| A hostile client could subscribe nearby chunks it did not map | server bounds subscriptions by distance and loaded state, so the reachable set is limited to chunks the player is standing among; client-side mapped filter is the primary rule (documented as a trust boundary, not a security boundary) |
| Client-side value freshness after moving away | entries expire (`staleAfterTicks`), then stop rendering and report `unavailable` instead of a stale number |

No fallback path is needed for the overlay itself: the highlighter API is a supported
callback interface. Only *registration* uses the mixin.

---

## 7. Open questions

1. **Threshold semantics.** Tensura exposes no tier vocabulary; the five presentation
   tiers are multipliers of the authoritative baseline and are configurable. Defaults
   are presentation choices, not Tensura rules.
2. **Behaviour under Xaero version bumps.** The mixin injection point is verified only
   for 26.6.0 / 1.47.0. No claim is made for other versions.
3. **Minimap hover text.** Not requested by the specification; the minimap highlighter
   implements `addBlockHighlightTooltips` as a no-op, keeping minimal scope.
4. **Far-away mapped chunks.** No authoritative value can exist while the chunk is
   unloaded, so those show no overlay. This is a property of Tensura's data model, not
   a defect of this integration, and is called out in the README.

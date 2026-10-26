# Compatibility

What this mod was built and verified against, what it needs, and what it
deliberately does not claim.

## 1. Verified versions

| Component | Version | Notes |
| --- | --- | --- |
| Minecraft | `1.21.1` | pinned; not tested on any other version |
| NeoForge | `21.1.259` | loader `[4,)`, NeoForge `[21.1.259,)` |
| Java | 21 | toolchain and `mixins.json` `compatibilityLevel: JAVA_21` |
| Tensura: Reincarnated | `2.0.1.3` | `modId` `tensura`, declares `[2.0.1.3,)` |
| Xaero's Minimap | `26.6.0` | `modId` `xaerominimap`, declares `[26.6.0,)` |
| Xaero's World Map | `1.47.0` | `modId` `xaeroworldmap`, declares `[1.47.0,)` |

Every mod is consumed as a `compileOnly` CurseMaven coordinate and is never
bundled into this mod's jar. The file ids in `gradle.properties` are what the
build resolves; the `*_mod_version` values are what those files contain and what
the `neoforge.mods.toml` ranges are generated from. **Bump both together.**

## 2. Required at runtime

NeoForge and Minecraft. Nothing else is required.

Two components are *feature* dependencies rather than mod dependencies:

- **Tensura: Reincarnated** — the only source of concentration data. Without it
  there is nothing to display; every feature is inert and nothing crashes.
- **Xaero's Minimap** and/or **Xaero's World Map** — the display targets. Each is
  independent: either, both, or neither.

## 3. Behaviour by installed set

| Installed | Result |
| --- | --- |
| Tensura + Minimap + World Map | Minimap overlay, world-map overlay, world-map hover |
| Tensura + Minimap | Minimap overlay only |
| Tensura + World Map | World-map overlay + hover |
| Tensura only | No map feature, no crash |
| Xaero only | No concentration feature, no crash |
| Neither | Mod loads, registers configs, does nothing |

Mechanisms:

- The server-side reader is gated on `ModList.isLoaded("tensura")` and the only
  Tensura-importing class is never loaded when Tensura is absent.
- Each map mixin config declares `requiredMods` for exactly its own map mod, so a
  missing map means the mixin is never applied.
- Each bridge checks `ModList` before touching its map's classes.

## 4. Version sensitivity — read before updating Xaero

Neither Xaero mod publishes an API artifact or a documented third-party overlay
API. This integration uses:

- their public `ChunkHighlighter` classes (a supported *callback* interface), and
- one mixin per map at `HighlighterRegistry.end()` — the only point where the
  highlighter list is still mutable, because both mods freeze it during session
  init and expose no registration call.

Consequences:

- The mixin target is verified **only** for `26.6.0` / `1.47.0`. The declared
  ranges (`[26.6.0,)`, `[1.47.0,)`) express "built against this or newer", not a
  promise that a later version still has the same class shape.
- If a future Xaero release moves or renames `HighlighterRegistry.end()`, the
  mixin fails loudly at client start when that map is installed. That is the
  intended failure mode: no silent half-working overlay.
- When updating either Xaero pin, re-verify the injection point and the
  `ChunkHighlighter` signatures before releasing, and record the new verified
  pair here.

Tensura is the same situation for its own surface: no API artifact, so this mod
compiles against the mod jar. The methods used are `getMagicule()`,
`getMaxMagicule()`, `TensuraStorages.getChunkFrom(...)`,
`ChunkStorage.CONFIG.baseMagicule` and `TensuraLevelEvents.CHUNK_TICK_POST`.
Every call is wrapped so a rename degrades to "no data" instead of a crash.

## 5. Multiplayer

- Concentration is **server-authoritative**. The client never computes or guesses
  it; there is no separate simulation.
- The client declares the chunks it has mapped; the server answers only those,
  only within its own radius cap, only for the dimension the player is standing
  in, only for chunks currently loaded, and only at a bounded rate.
- Unmapped chunks are not disclosed by rendering, tooltips, cache contents or
  packet contents.
- A dedicated server never loads any client class, and a client-side Xaero mod is
  never required on the server.

## 6. Known limitations

| Limitation | Cause |
| --- | --- |
| Far-away mapped chunks show only their **last known** value, dimmer, and never update while unloaded | Tensura has no value for an unloaded chunk and this mod never loads or generates one; the remembered observation is the newest thing that existed |
| Live coverage is limited to the area around the player | the server answers within `maxSubscribeRadiusChunks`; there is no bulk history API. Everything else comes from the client's per-world history file. |
| Remembered values are as old as the last visit | by construction: they are real observations from when the chunk was loaded, never extrapolated forward |
| Overlay is per-chunk, not per-block | Tensura's stored value is per chunk; per-block modifiers exist only for a specific position query |
| Minimap hover text is not implemented | only world-map hover was specified; the minimap highlighter implements its tooltip hook as a no-op |
| Xaero overlay registration relies on one mixin per map | neither mod offers a third-party registration call in the pinned versions |
| Tiers are presentation, not gameplay | Tensura exposes a baseline, not named concentration classes |

## 7. Not claimed

- Any Minecraft or NeoForge version other than 1.21.1 / 21.1.259.
- Any Xaero or Tensura version other than the pinned ones.
- Compatibility with Tensura RPG System, or with any Tensura fork. This targets
  `tensura` (Tensura: Reincarnated) only.
- Any redistribution of Tensura, ManasCore, Architectury, GeckoLib, SmartBrainLib,
  TerraBlender or Xaero binaries. All are All Rights Reserved and are resolved
  from CurseMaven at build time; none is committed or bundled.

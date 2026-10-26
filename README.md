# Tensura × Xaero Integration

See the magicule in the air where you have already been. This addon tints mapped
chunks on [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap)
and [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
according to how magicule-rich the ambient air is in that chunk — and tells you the
exact number when you point at a chunk on the world map.

## What it does

- **A translucent overlay on both maps.** Chunks whose ambient concentration is
  above the normal baseline are tinted, and the tint grows stronger the further
  above baseline they are. Normal chunks are left untouched, so the map still looks
  like a map.
- **Live values.** Tensura's concentration moves as magicule regenerates and as
  blocks and entities use or emit it. The tint and the number follow, without you
  having to reopen the map.
- **Exact numbers on hover.** Point at a chunk on the world map and the tooltip
  shows its concentration and how that compares to normal, for example
  `Magicule Concentration: 1450 — Moderately Elevated`.
- **Only what you have explored.** Nothing is shown, sent or stored for chunks you
  have not mapped. Your map only reveals what your map already knows.
- **Nothing is simulated.** The values are Tensura's own. This addon only reads and
  draws them; it never recalculates, guesses or changes concentration.

## Getting it in-game

Install the mod on the **client**, and on the server if you play multiplayer. No
commands, items or world-setup steps: if Tensura and at least one Xaero map are
present, the overlay appears on the chunks you have explored.

Each map is independent — the overlay works with only the minimap, only the world
map, or both — and the mod does nothing at all if Tensura is not installed.

## What your map remembers

Tensura only holds a concentration value for chunks the server currently has
loaded. Rather than dropping the rest, this addon remembers the last value it saw
for every chunk you have mapped: when a chunk unloads or you travel away, its
overlay stays but fades, and hovering says `(last known)` instead of passing an
old number off as a current one. Walk back and it brightens again as soon as the
server can answer for it.

That history is stored in your own game folder, one file per world, and only
ever contains chunks your maps had already explored. Deleting it is harmless —
the maps simply go blank outside the loaded area again.

## Configuration

Two config files, both optional to touch:

- **Client** — which overlays and hover are on, colours and opacity (which can
  override the pack's), how bright last-known values are, how much history to
  remember, and how far around you data is requested.
- **Server** — a master switch for sending concentration at all, per-player caps,
  the distance limit, and how much work reconciliation may spend per cycle.

## Per-dimension tiers

Dimensions do not share a magicule scale — the nether and the end are hotter than
overworld air, and the hell dimension is orders of magnitude hotter still. So
"elevated" is judged **per dimension**: a chunk is compared with what normal looks
like where you are, not with one global number. The server decides the tier and
sends it with the value, so every player on a server sees the same thing.

Those tiers ship as datapack entries and are the intended knob for pack authors:
`data/<pack>/magicule_tiers/*.json` sets a dimension's normal and, if needed, its
own band boundaries. See
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md#datapack-per-dimension-tiers).

Defaults are chosen to look good and stay cheap. Every key, default and effect is
documented in [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md).

## Dependencies

| | Mod | Notes |
| --- | --- | --- |
| Required | Minecraft 1.21.1 | — |
| Required | NeoForge 21.1.259+ | — |
| Required | [Tensura: Reincarnated](https://www.curseforge.com/minecraft/mc-mods/tensura-reincarnated) 2.0.1.3+ | the source of every value shown; without it this addon does nothing |
| Optional | [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) 26.6.0+ | minimap overlay |
| Optional | [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map) 1.47.0+ | world-map overlay and hover |

## Known limitations

- Only the area the server still has loaded has *live* values; everything else shows what was last observed there (see above).
- The bands (*Slightly / Moderately / Highly / Exceptionally Elevated*) are display
  bands relative to Tensura's baseline. Tensura itself has no named tiers, so these
  are a reading aid, not a game rule.
- The integration is pinned to the Xaero and Tensura versions listed above. Later
  versions may move the map hooks it needs; see
  [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md).
- Multiplayer values are server-authoritative, and a server can switch the whole
  feature off.

## Building from source

Java 21 and the committed Gradle wrapper are all that is needed; every dependency
resolves from CurseMaven, including Tensura's own library closure.

```bash
./gradlew build                # compiles, runs the JUnit suite, produces the jar
./gradlew runGameTestServer    # headless GameTests against the real Tensura jar
```

Deeper detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/CONFIGURATION.md`](docs/CONFIGURATION.md),
[`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md),
[`docs/TESTING.md`](docs/TESTING.md),
[`docs/RECONNAISSANCE.md`](docs/RECONNAISSANCE.md) and
[`CHANGELOG.md`](CHANGELOG.md).

## Credits

- [Tensura: Reincarnated](https://www.curseforge.com/minecraft/mc-mods/tensura-reincarnated)
  by ManasMods — every concentration value shown here comes from its API.
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) and
  [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
  by Xaero96 — the maps this addon draws on.
- [NeoForge](https://neoforged.net/) — the mod loader.

## License

GNU Lesser General Public License v2.1 — see [`LICENSE`](LICENSE).
Copyright (c) 2026 Bitwise Madness.

No third-party mod binary is bundled, redistributed or committed by this project.

## AI disclosure

Parts of this project were written with the assistance of AI coding tools.
AI-assisted code, data and documentation ship only after human review by the
maintainers.

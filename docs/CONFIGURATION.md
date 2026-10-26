# Configuration

Every setting the mod adds, what it does, and where it lives.

Two files are created:

| File | Type | Where |
| --- | --- | --- |
| `config/tensura_xaero_integration-client.toml` | client | per game instance |
| `serverconfig/tensura_xaero_integration-server.toml` | server | per world save |

The client file holds what is drawn and how it looks, plus how much data this
client asks for. The server file holds what may be sent and how much work the
server may spend, so a pack can tune a world without touching any client.

Badly written datapack files are reported in the log and skipped, so one typo can
never empty the tier table; unparsable colours in the client config fall back to
the server's colour. Neither ever throws.

---

## Client (`tensura_xaero_integration-client.toml`)

### `[overlays]`

| Key | Default | Effect |
| --- | --- | --- |
| `minimap` | `true` | Draw the concentration overlay on Xaero's Minimap. |
| `worldMap` | `true` | Draw the concentration overlay on Xaero's World Map. |
| `worldMapHover` | `true` | Show the hovered chunk's concentration as world-map tooltip lines. |

Each overlay also needs its map mod installed; the switch alone cannot create
one.

### `[palette]`

The four bands are fixed; **where they sit is not** — that is per dimension and
lives in the datapack (see below), because dimensions do not share a magicule
scale. A chunk at its dimension's normal concentration is *Normal* and is never
painted.

| Key | Default | Band |
| --- | --- | --- |
| `slightlyElevatedColor` | *(empty)* | Slightly Elevated |
| `slightlyElevatedOpacity` | `-1` | |
| `moderatelyElevatedColor` | *(empty)* | Moderately Elevated |
| `moderatelyElevatedOpacity` | `-1` | |
| `highlyElevatedColor` | *(empty)* | Highly Elevated |
| `highlyElevatedOpacity` | `-1` | |
| `exceptionallyElevatedColor` | *(empty)* | Exceptionally Elevated |
| `exceptionallyElevatedOpacity` | `-1` | |

An **empty colour** and an **opacity of `-1`** mean "use the value the server's
datapack supplies". Set a colour to `#RRGGBB` to override that band locally (for
contrast, or a colour-blind friendly ramp); opacity is `0.0` (invisible) to `1.0`.
The shipped config overrides nothing, so a pack's palette arrives as authored.

A normal chunk is never painted, whatever its opacity would be — it still reports
a colour so the world-map hover line can appear. Keep opacities low: the overlay
is meant to tint terrain, not hide it.

### `[freshness]`

| Key | Default | Effect |
| --- | --- | --- |
| `historicalOpacityScale` | `0.45` | How bright a last-known value is drawn, as a fraction of its band's opacity. `0.0` keeps remembered values off the map entirely (they still answer the hover line); `1.0` draws them exactly as bright as a current value. |

How long a value counts as **current** is not a client setting. It is derived from
the server's own confirmation cadence — three confirmations, floored at 3 seconds —
because the server is the only side that knows how often it will confirm a chunk
again. Keeping a second number here would let the two disagree: too short and values
the server is still confirming would dim, too long and values it stopped confirming
would claim to be current.

The window is measured in **milliseconds of real time**, and the server reports how
long one of its confirmations actually takes, not just how many ticks it waits. That
distinction matters on a heavy pack: a server that prints `Can't keep up! ... 120
ticks behind` stretches its 200-tick interval to many seconds, and a window counted
in client ticks then expires before the next confirmation arrives. Three beats of
slack is what absorbs the remaining stutters. The measured cadence is logged on join,
next to the baseline.

This is the line between "the server says this is what it is now" and "this is
what it was when I was last there". It is a display rule, not a data rule: the
number is never discarded by it, only relabelled.

### `[persistence]`

| Key | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Remember concentrations between sessions, per world, so chunks that are no longer loaded still show what was last observed there. |
| `maxEntriesPerWorld` | `20000` | Most chunks remembered per world; bounds both the file and memory. Least recently seen chunks are dropped first. `0` disables remembering without touching the switch. |

History lives in `<game directory>/tensura_xaero_integration/<world>-<hash>.bin`,
separate per world and including every dimension. Only chunks your maps have
mapped ever enter it, so replaying it cannot reveal anything your map did not
already know. Deleting a file is safe: it is treated as "no history".

### `[subscription]`

| Key | Default | Effect |
| --- | --- | --- |
| `radiusChunks` | `10` | Radius around the player for which **mapped** chunks are requested. |
| `intervalTicks` | `20` | How often the mapped set is recomputed and sent. |
| `batchSize` | `256` | Chunk entries per subscription packet. |

Only chunks the map says are mapped inside this radius are requested, so the
mapped-chunk rule is enforced before a byte leaves the client. The server applies
its own, independent radius cap — if the server's cap is smaller than this value,
the missing chunks simply never arrive.

---

## Datapack: per-dimension tiers

This is where a dimension's bands are defined. It is **server-side and
server-authoritative**: the server classifies each chunk and sends the band with
the value, so every player sees the same tiers and no client config can change
them.

### Why per dimension

Tensura's own data gives dimensions wildly different scales: the overworld level
adds nothing, the nether multiplies by `1.2` and adds up to `+4500` in crimson
forests, the end multiplies by `1.5` and adds `+4500`, and `tensura:hell` adds
`100000` before any biome. One global baseline turns the nether and the end into a
single flat colour, which is what this datapack exists to fix.

### Files

`data/<namespace>/magicule_tiers/*.json` — merged from every loaded datapack, so a
pack can add a dimension without restating the others.

```json
{
  "dimensions": ["minecraft:the_nether"],
  "baseline": 600.0,
  "bands": {
    "slightly_elevated": 1.25,
    "moderately_elevated": 2.0,
    "highly_elevated": 4.0,
    "exceptionally_elevated": 8.0
  }
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `dimensions` | yes | One or more dimension ids this entry describes. |
| `baseline` | yes | The concentration that counts as *normal* there; finite and `> 0`. |
| `bands` | no | Multipliers of the baseline. All four keys or none — a partial ladder is rejected. Defaults to `1.25 / 2 / 4 / 8`. |

Boundaries are inclusive on the lower band, and anything at or below the baseline
is *Normal* and unpainted. A dimension no file mentions falls back to Tensura's
own baseline with the default ladder, i.e. the old single-baseline behaviour; if
that baseline is unreadable the chunk is shown with no band rather than a guessed
one.

A file may instead carry `palette`:

```json
{
  "palette": {
    "slightly_elevated": { "color": "#29B6F6", "opacity": 0.10 }
  }
}
```

Bands left out keep their defaults, which lets a pack theme one band without
restating the rest. These are the defaults the client's `[palette]` overrides
when it names a colour.

`/reload` re-reads the datapack, so a pack can retune a dimension without a
restart. Problems (missing baseline, bad ladder, malformed dimension id, a
dimension declared twice) are logged with the file that caused them and that file
is skipped; the rest still applies.

### Shipped entries

| Dimension | Baseline | Why |
| --- | --- | --- |
| `minecraft:overworld` | `500` | Tensura's `baseMagicule`; `dark_forest` reaches `2000`, `deep_dark` `2500`. |
| `minecraft:the_nether` | `600` | `baseMagicule × 1.2`; biomes add `1500`–`4500`. |
| `minecraft:the_end` | `750` | `baseMagicule × 1.5`; end biomes add `4500`. |
| `tensura:hell` | `100500` | `+100000` level modifier; underworld biomes add `500`–`3500`, so its ladder is tight (`1.005`–`1.03`). |
| `tensura:labyrinth` | `30000` | `+29500` level modifier, flat void world: uniform, so the default ladder is right. Magic engines subtract from it, which is below normal and therefore unpainted. |
| `tensura:boss_area` | `500` | Flat void arena, no modifiers. |
| `iceandfire:dread_land` | `50500` | `+50000` from `tensura_iaf`; both dread biomes contribute nothing, so uniform. |
| `mysticism:elemental_realm` | `100500` | `+100000` level modifier; its seven biomes add `500`–`3500`, so the same tight ladder as hell. |
| `mysticism:kamui_dimension` | `500` | Single fixed biome with no magicule modifier. |
| `irons_spellbooks:pocket_dimension` | `500` | Flat water pocket, no modifiers. |
| `tensura_all_the_adventure:reincarnation_trial` | `500` | Flat void trial, no modifiers. |

A uniform dimension paints nothing — every chunk sits at its normal, which is the
honest reading. Where a dimension *does* vary (`dark_forest`, `deep_dark`, the
nether biomes, the elemental realm) the ladder is what makes the difference
visible; a pack that wants a flat dimension to show something lower its baseline
there.

These assume Tensura's shipped `baseMagicule` of `500`. If a pack changes it, the
nether and end figures scale with it and the hell/labyrinth ones need re-tuning —
which is exactly the knob this datapack provides.

---

## Server (`serverconfig/tensura_xaero_integration-server.toml`)

### `[sync]`

| Key | Default | Effect |
| --- | --- | --- |
| `enabled` | `true` | Master switch. Off: no concentration data leaves the server and every map stays uncoloured. |

### `[subscriptions]`

| Key | Default | Effect |
| --- | --- | --- |
| `maxSubscribedChunksPerPlayer` | `2048` | Cap on one player's subscription set; bounds both bookkeeping and what a client can hold. |
| `maxSubscribeRadiusChunks` | `16` | Largest distance in chunks between player and an answerable chunk. The hard limit that keeps a modified client from probing chunks it is not standing among. Must be at least the client's `radiusChunks` or nothing is sent. |
| `minSubscribePacketIntervalTicks` | `5` | Minimum ticks between two subscription packets from one player. |

### `[refresh]`

| Key | Default | Effect |
| --- | --- | --- |
| `reconcileIntervalTicks` | `100` | How often the periodic sweep runs. |
| `reconcileBudgetChunksPerPlayer` | `256` | Most subscribed chunks re-read per player per sweep. The cursor rotates, so every subscription is visited over successive sweeps. |
| `minUpdateIntervalTicks` | `20` | Minimum ticks between two sends for the same chunk. A change inside the window stays pending — it is delayed, never dropped. |
| `refreshIntervalTicks` | `200` | How often a subscribed chunk whose value has **not** changed is confirmed again. A chunk parked at its magicule ceiling never changes, so without this heartbeat it would be sent once and then count as last known on the client. Clients derive their freshness window from this cadence (three times the interval, measured in real time), so raising it also makes values stay current for longer. |
| `maxUpdatesPerPacket` | `256` | Chunk updates batched into one packet. |

---

## Which side owns which decision

- **Visual** (colours, opacity, hover) is client-only: it changes only what this
  player sees. The bands themselves are server-side (the datapack), so two
  players on one server cannot disagree about a chunk's tier.
- **Disclosure** (`sync.enabled`, the caps, the radius) is server-side: a client
  cannot widen it, and a pack operator can switch the feature off world-wide
  without client edits.
- **Request rate** exists on both sides on purpose. The client decides what it
  wants; the server decides what it will answer and how often, and clamps
  anything the client asks for beyond that.

## Suggested tuning

| Goal | Change |
| --- | --- |
| Quieter map | lower the opacities, or override them in the client `[palette]` |
| See subtler elevations | lower `slightly_elevated` in that dimension's datapack entry |
| Cheaper on a busy server | raise `reconcileIntervalTicks`, lower `reconcileBudgetChunksPerPlayer` |
| Fewer packets | raise `minUpdateIntervalTicks`, raise `refreshIntervalTicks`, raise `maxUpdatesPerPacket` |
| Larger overlay area | raise client `radiusChunks` **and** server `maxSubscribeRadiusChunks` |
| Hide the feature on a server | `sync.enabled = false` |
| Remember less history | lower `persistence.maxEntriesPerWorld` |
| Forget history entirely | delete the world file, or set `persistence.maxEntriesPerWorld = 0` |
| Make old values fainter | lower `freshness.historicalOpacityScale` |

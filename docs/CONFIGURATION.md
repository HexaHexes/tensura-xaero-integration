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

Badly ordered thresholds fall back to the defaults with a single warning;
unparsable colours fall back to that band's default colour. Neither ever throws.

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

### `[thresholds]`

Band boundaries as **multiples of Tensura's configured baseline**
(`area_magicule_config: baseMagicule`), not absolute magicule amounts. Multipliers
keeps the bands meaningful on a server whose baseline is not the default `500`.

| Key | Default | Band starts above |
| --- | --- | --- |
| `slightlyElevatedMultiplier` | `1.25` | 1.0× baseline |
| `moderatelyElevatedMultiplier` | `2.0` | `slightlyElevatedMultiplier` |
| `highlyElevatedMultiplier` | `4.0` | `moderatelyElevatedMultiplier` |
| `exceptionallyElevatedMultiplier` | `8.0` | `highlyElevatedMultiplier` |

Values must be finite, `>= 1.0`, and strictly increasing. Boundaries are
inclusive on the lower band: exactly `1.25 ×` baseline is still *Slightly
Elevated*. At or below `1.0 ×` baseline a chunk is *Normal* and nothing is drawn.

These are **presentation bands**. Tensura defines no concentration tiers; the
defaults only spread the palette over the range Tensura actually produces.

### `[palette]`

| Key | Default | Band |
| --- | --- | --- |
| `slightlyElevatedColor` | `#29B6F6` | Slightly Elevated |
| `slightlyElevatedOpacity` | `0.10` | |
| `moderatelyElevatedColor` | `#5C6BC0` | Moderately Elevated |
| `moderatelyElevatedOpacity` | `0.20` | |
| `highlyElevatedColor` | `#AB47BC` | Highly Elevated |
| `highlyElevatedOpacity` | `0.32` | |
| `exceptionallyElevatedColor` | `#EC407A` | Exceptionally Elevated |
| `exceptionallyElevatedOpacity` | `0.45` | |

Colours are `#RRGGBB`; opacity is `0.0` (invisible) to `1.0`. A normal chunk is
never painted, whatever its opacity would be — it still reports a colour so the
world-map hover line can appear. Keep opacities low: the overlay is meant to tint
terrain, not hide it.

### `[baseline]`

| Key | Default | Effect |
| --- | --- | --- |
| `fallback` | `500.0` | Baseline used **only** until the server reports Tensura's configured value. |

`500` matches Tensura's shipped `baseMagicule`. It is a fallback, never a
definition: as soon as the server's `BaselinePayload` arrives, that value is used
and this one is ignored for the rest of the session.

### `[freshness]`

| Key | Default | Effect |
| --- | --- | --- |
| `staleAfterTicks` | `600` | After this many client ticks without a refresh, a value stops painting and the hover line reports it unavailable instead of showing it as current. 600 ticks ≈ 30 s at 20 tps. |
| `purgeAfterTicks` | `2400` | After this, the entry is dropped from memory entirely. Clamped up to at least `staleAfterTicks`. |

Staleness is what stops a chunk you walked away from from showing an old number
as if it were live: the chunk unloads server-side, reconciliation reports it
unavailable, and this TTL covers the gap before that answer arrives.

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
| `maxUpdatesPerPacket` | `256` | Chunk updates batched into one packet. |

---

## Which side owns which decision

- **Visual** (colours, opacity, thresholds, hover) is client-only: it changes
  only what this player sees.
- **Disclosure** (`sync.enabled`, the caps, the radius) is server-side: a client
  cannot widen it, and a pack operator can switch the feature off world-wide
  without client edits.
- **Request rate** exists on both sides on purpose. The client decides what it
  wants; the server decides what it will answer and how often, and clamps
  anything the client asks for beyond that.

## Suggested tuning

| Goal | Change |
| --- | --- |
| Quieter map | lower the opacities, raise `slightlyElevatedMultiplier` |
| See subtler elevations | lower `slightlyElevatedMultiplier` (but `> 1.0`) |
| Cheaper on a busy server | raise `reconcileIntervalTicks`, lower `reconcileBudgetChunksPerPlayer` |
| Fewer packets | raise `minUpdateIntervalTicks`, raise `maxUpdatesPerPacket` |
| Larger overlay area | raise client `radiusChunks` **and** server `maxSubscribeRadiusChunks` |
| Hide the feature on a server | `sync.enabled = false` |

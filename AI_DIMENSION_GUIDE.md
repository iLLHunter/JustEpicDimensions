# AI assistant guide: creating dimension files for Just Epic Dimensions

> **For people:** Link this page or Copy this entire file and paste it into an AI assistant (ChatGPT, Gemini or similar) at the start of a new chat. Then describe the dimension you want, for example *"a peaceful flat world for building"* or *"a dark cave world full of monsters"*. The assistant will walk you through the choices one at a time and write the files for you. You don't need to know JSON.
>
> Written for Just Epic Dimensions (JED) 2.x on Minecraft 1.21.1 with NeoForge.

---

## Instructions for the AI assistant

You are helping someone create a custom dimension for the Minecraft mod **Just Epic Dimensions (JED)**. JED reads JSON files from the `config/justepicdimensions/` folder and turns them into a new world players can visit. Your job is to interview the user, then write those files. Follow these rules:

1. **Ask, don't assume.** Go through the steps in [The interview](#the-interview) in order, **one step per message**. For each step, offer a short numbered list of choices, mark the default, and accept answers like "2" or "default". If the user says they don't care, use the default and move on. If their first message already answers a step, confirm it briefly instead of asking again.
2. **Only use what this guide lists.** JED rejects unknown fields, misspelled values and duplicate keys, and refuses to load the file. Never invent a field or value. If the user wants something this guide doesn't cover, say so plainly rather than guessing. The user can set those up later in game with the dimension builder (`/jed gui`).
3. **Write strict JSON.** No comments, no trailing commas, double quotes only, every key once.
4. **Never add an `id` field.** A dimension's ID comes from its file path.
5. **The names are the user's.** This guide uses `mypack` and names like `build_world` only as examples. Ask the user for their own namespace and name and use those everywhere; never copy `mypack` into their files unless they choose it.
6. **Finish with the files and the commands.** At the end, show each file in its own code block with its full path above it, then the activation steps from [Activating the dimension](#activating-the-dimension).
7. **Keep it short and plain.** The user may not know Minecraft's technical terms. Explain a choice in one sentence.

---

## The files

| File                                                                                             | Needed                                    | Holds                                                                                    |
| ------------------------------------------------------------------------------------------------ | ----------------------------------------- | ---------------------------------------------------------------------------------------- |
| `config/justepicdimensions/data/<namespace>/dimension/<name>.json`                               | Always                                    | Terrain: the generator, the biomes and the dimension type. Minecraft's own format.       |
| `config/justepicdimensions/data/<namespace>/justepicdimensions/dimension_properties/<name>.json` | Almost always                             | JED settings: display name, arrival point, time, weather, mobs, inventory, look, travel. |
| `config/justepicdimensions/data/<namespace>/dimension_type/<name>.json`                          | Only for a custom dimension type (step 3) | Sky, ceiling, height range, darkness, beds.                                              |
| `config/justepicdimensions/data/<namespace>/justepicdimensions/<kind>/<file>.json`               | Only for extras (step 13)                 | Reusable JED files such as access rules and starting kits.                               |

- The dimension's ID is `<namespace>:<name>`. The dimension file and the properties file must use the **same** namespace and name.
- Paths start in the server folder, or in single player in the game folder that contains `mods/` and `config/`.
- If the server owner has turned on per-world configuration (`usePerWorldDimensionConfig = true` in `config/justepicdimensions/justepicdimensions.toml`), the folder is `<world folder>/serverconfig/justepicdimensions/` instead of `config/justepicdimensions/`.
- Every properties file starts with `"format_version": 1`. It then contains only the sections the user chose. Leave a section out to keep Minecraft's normal behaviour.

---

## The interview

### Step 1: Name it

Ask for:

- **Namespace**: one short word for the user's pack or server, e.g. their server's name. Use lowercase letters, digits, `_`, `-` and `.` only. Not `minecraft`. Avoid `justepicdimensions`, which JED's own showcase uses.
- **Name**: the dimension's file name, with the same characters, e.g. `sky_islands`.
- **Display name** (optional): what players see in lists, e.g. "Sky Islands", 1 to 256 characters.

The display name goes in the properties file:

```json
{
  "format_version": 1,
  "display_name": "Sky Islands"
}
```

### Step 2: Terrain

Ask which kind of terrain they want:

1. **Normal Minecraft terrain with every Overworld biome** (default)
2. **Normal terrain, one biome only**, e.g. all snowy plains or all jungle
3. **Normal terrain, a hand-picked mix of biomes**
4. **Amplified** (huge mountains) or **large biomes**
5. **Floating islands** in the sky
6. **Underground caves** with a stone ceiling
7. **Nether-style**
8. **End-style** (islands over the void, optionally with its own dragon)
9. **Flat world**, built from layers they choose
10. **Void** (empty), for a skyblock-style start

Then write the dimension file from the matching template below.

**1. Normal terrain, all biomes:**

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" }
  }
}
```

**2. One biome.** Replace the biome with any biome ID, such as `minecraft:snowy_plains`, `minecraft:jungle`, `minecraft:desert`, `minecraft:cherry_grove`, `minecraft:mushroom_fields` or `minecraft:dark_forest`:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": { "type": "minecraft:fixed", "biome": "minecraft:snowy_plains" }
  }
}
```

**3. A biome mix.** This is JED's weighted mix. A biome with weight 3 covers about three times the area of one with weight 1. `region_size` is roughly how wide each biome patch is, in blocks (64 to 512 is typical). `shape` is `irregular` (natural-looking, default) or `grid` (square patches):

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": {
      "type": "justepicdimensions:weighted_regions",
      "region_size": 256,
      "shape": "irregular",
      "entries": [
        { "biome": "minecraft:plains", "weight": 3 },
        { "biome": "minecraft:forest", "weight": 2 },
        { "biome": "minecraft:birch_forest", "weight": 1 }
      ]
    }
  }
}
```

The mix can also change with height. Add `"height_bands"`, a list of `{ "min_y": …, "max_y": …, "entries": [ … ] }`; `min_y` is inclusive, `max_y` is exclusive, and bands must not overlap. Outside the bands, the main `entries` apply. Example: `"height_bands": [ { "min_y": -64, "max_y": 0, "entries": [ { "biome": "minecraft:lush_caves", "weight": 1 } ] } ]`.

**4. Amplified or large biomes.** Use template 1 and change `"settings"` to `"minecraft:amplified"` or `"minecraft:large_biomes"`:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:amplified",
    "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" }
  }
}
```

**5. Floating islands.** Players can fall off the edge, so suggest the "fall into the void" travel rule in step 12:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:floating_islands",
    "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" }
  }
}
```

**6. Underground caves with a ceiling.** Use the `cavern` arrival mode in step 4. The cave biomes `minecraft:lush_caves`, `minecraft:dripstone_caves` and `minecraft:deep_dark` suit it:

```json
{
  "type": "minecraft:overworld_caves",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:caves",
    "biome_source": {
      "type": "justepicdimensions:weighted_regions",
      "region_size": 128,
      "shape": "irregular",
      "entries": [
        { "biome": "minecraft:lush_caves", "weight": 1 },
        { "biome": "minecraft:dripstone_caves", "weight": 1 }
      ]
    }
  }
}
```

**7. Nether-style.** Use the `cavern` arrival mode in step 4 and the Nether provider (see [Providers](#providers)). The `the_nether` type also makes water evaporate and beds explode, and, like the real Nether, its coordinates are scaled by 8 for portals and scaled travel:

```json
{
  "type": "minecraft:the_nether",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:nether",
    "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:nether" }
  }
}
```

**8. End-style.** Use the End provider and the `end` section in step 13 for the dragon fight. The main island is at X 0, Z 0:

```json
{
  "type": "minecraft:the_end",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:end",
    "biome_source": { "type": "minecraft:the_end" }
  }
}
```

**9. Flat world.** List layers from the **bottom up**. Layers start at the bottom of the world, which is Y −64 for the `minecraft:overworld` type, so the ground surface is at −64 plus the total height of all layers. The layers below add up to 129, putting the grass at Y 64 like normal ground level. `biome` must be a real biome ID; JED refuses an unknown one. Keep `lakes`, `features` and `structure_overrides` as shown unless the user wants trees, lakes or villages; `features: true` adds the biome's trees and plants:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:plains",
      "layers": [
        { "block": "minecraft:bedrock", "height": 1 },
        { "block": "minecraft:stone", "height": 124 },
        { "block": "minecraft:dirt", "height": 3 },
        { "block": "minecraft:grass_block", "height": 1 }
      ],
      "lakes": false,
      "features": false,
      "structure_overrides": []
    }
  }
}
```

**10. Void.** A flat world with no layers. There is nothing to stand on, so use a `fixed` arrival point with `fallback_block` (one glass block) or a spawn building in step 4, and the "fall into the void" travel rule in step 12:

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:the_void",
      "layers": [],
      "lakes": false,
      "features": false,
      "structure_overrides": []
    }
  }
}
```

### Step 3: Sky, height and darkness (dimension type)

The `"type"` in the dimension file sets the sky, ceiling, height range and how beds behave. **Most users should keep the type that matches their terrain** (the templates above already have it):

| Type                        | Sky and light                      | Height (Y) | Notes                                                              |
| --------------------------- | ---------------------------------- | ---------- | ------------------------------------------------------------------ |
| `minecraft:overworld`       | Normal sky, day and night          | −64 to 319 | Beds work. Default.                                                |
| `minecraft:overworld_caves` | Like the Overworld, with a ceiling | −64 to 319 | Use with `minecraft:caves`.                                        |
| `minecraft:the_nether`      | No sky, always dim                 | 0 to 255   | Beds explode, water evaporates, 1 block here = 8 in the Overworld. |
| `minecraft:the_end`         | End sky, no day or night           | 0 to 255   | Beds explode.                                                      |

Many "look" changes don't need a custom type. Sky colour, fog, sun size and music are in step 11, and freezing time is in step 5. Offer a **custom type** only when the user wants something a type controls, such as no skylight at all, a fixed sun position, different darkness, or beds that never explode. To make one, copy this file (the normal Overworld values) to `dimension_type/<name>.json`, change only the fields they asked for, and point the dimension file at it with `"type": "<namespace>:<name>"`:

```json
{
  "ambient_light": 0.0,
  "bed_works": true,
  "coordinate_scale": 1.0,
  "effects": "minecraft:overworld",
  "has_ceiling": false,
  "has_raids": true,
  "has_skylight": true,
  "height": 384,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "logical_height": 384,
  "min_y": -64,
  "monster_spawn_block_light_limit": 0,
  "monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },
  "natural": true,
  "piglin_safe": false,
  "respawn_anchor_works": false,
  "ultrawarm": false
}
```

| Field                                                                                     | Meaning                                                                                                                                                                                                                                                                |
| ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ambient_light`                                                                           | 0.0 to 1.0. How bright unlit places look. The Overworld is 0.0; the Nether is 0.1.                                                                                                                                                                                     |
| `bed_works`                                                                               | `false` makes beds explode.                                                                                                                                                                                                                                            |
| `respawn_anchor_works`                                                                    | `true` lets respawn anchors work.                                                                                                                                                                                                                                      |
| `coordinate_scale`                                                                        | Distance multiplier compared with the Overworld (the Nether uses 8.0).                                                                                                                                                                                                 |
| `effects`                                                                                 | Sky style: `minecraft:overworld`, `minecraft:the_nether` or `minecraft:the_end`.                                                                                                                                                                                       |
| `fixed_time`                                                                              | Optional, add it to freeze the sun and moon at that time (0 to 24000, e.g. 18000 = midnight).                                                                                                                                                                          |
| `has_ceiling`                                                                             | `true` for a roofed world.                                                                                                                                                                                                                                             |
| `has_skylight`                                                                            | `false` means no light from the sky at all.                                                                                                                                                                                                                            |
| `min_y`, `height`, `logical_height`                                                       | Build range. Keep these the same as the terrain's: −64 and 384 for overworld, amplified, large biomes and caves terrain; 0 and 256 for Nether, End and floating-islands terrain. `logical_height` is how high portals and chorus fruit can take you, at most `height`. |
| `natural`                                                                                 | `true` makes compasses and clocks work, and lets Nether portals spawn zombified piglins.                                                                                                                                                                               |
| `piglin_safe`                                                                             | `false` makes piglins turn into zombified piglins over time.                                                                                                                                                                                                           |
| `ultrawarm`                                                                               | `true` makes water evaporate and lava flow faster.                                                                                                                                                                                                                     |
| `has_raids`, `infiniburn`, `monster_spawn_light_level`, `monster_spawn_block_light_limit` | Keep the copied values unless the user asks.                                                                                                                                                                                                                           |

### Step 4: Arrival point

Where players appear when they travel to the dimension. JED finds the spot once and saves it. Choices:

1. **On the ground, found automatically** (`surface`). The default for any world with ground.
2. **Exact coordinates** (`fixed`). Needs `x`, `y`, `z`, which are the block the player's feet are in. For void worlds add `"fallback_block": true`, which places one glass block under the spot if it is air.
3. **Inside a cave** (`cavern`). For Nether-style and cave worlds with a ceiling.
4. **Minecraft's default** (`native`). Not recommended; secondary dimensions otherwise use the Overworld's spawn point.

Always include `"reset_fall_distance": true` and `"reset_velocity": true`, so players don't take fall damage they carried from the world they left:

```json
{
  "spawn": {
    "mode": "surface",
    "reset_fall_distance": true,
    "reset_velocity": true
  }
}
```

```json
{
  "spawn": {
    "mode": "fixed",
    "x": 0,
    "y": 64,
    "z": 0,
    "fallback_block": true,
    "reset_fall_distance": true,
    "reset_velocity": true
  }
}
```

```json
{
  "spawn": {
    "mode": "cavern",
    "reset_fall_distance": true,
    "reset_velocity": true
  }
}
```

Optional `spawn` fields:

| Field                | Meaning                                                                                 |
| -------------------- | --------------------------------------------------------------------------------------- |
| `x`, `z`             | With `surface` or `cavern`: search this column instead of the default.                  |
| `min_y`, `max_y`     | Limit the height range of a `surface` or `cavern` search.                               |
| `angle`              | Direction players face, in degrees (default 0).                                         |
| `y_offset`           | Raise the saved point by this many blocks, e.g. for a platform.                         |
| `ignore_suitability` | `true` accepts any solid floor, not just grass-type blocks. Already on for flat worlds. |
| `fallback_block`     | `true` places one glass block under the point if it is air (default `false`).           |

**A building at the arrival point (optional):** if the user has a structure file (`.nbt` from a structure block, or a Sponge v3 `.schem`), JED can paste it once when the dimension is first created. Put `.nbt` files at `config/justepicdimensions/data/<namespace>/structure/<file>.nbt` and `.schem` files at `config/justepicdimensions/data/<namespace>/justepicdimensions/schematics/<file>.schem`, then add:

```json
{
  "spawn_structure": {
    "asset": "mypack:spawn_house.nbt",
    "centered": true,
    "offset": [0, -1, 0]
  }
}
```

`offset` `[0, -1, 0]` sinks the building one block so its floor lines up with the arrival point. Optional: `rotation` (`none`, `clockwise_90`, `clockwise_180`, `counterclockwise_90`), `mirror` (`none`, `left_right`, `front_back`), `load_around` (0 to 128, default 16) and `paste_biomes` (default `false`).

### Step 5: Time of day

Choices:

1. **Same clock as the Overworld** (default). Write nothing.
2. **Its own clock, running normally.** Add `"state": {}` (see the note below).
3. **Time stopped**, e.g. always noon or always night. Needs `state`.
4. **Longer or shorter days and nights.** Needs `state` and `time`.

> **About `state`:** without a `state` section, the dimension shares the Overworld's time, weather, difficulty and game rules. Adding `state` gives it its own. Put it in **before** the dimension is first created; adding it later needs `/jed unload`, `/jed reload` and `/jed load` for that dimension. `state.initial` is applied **once**, when the dimension is first created; `state.on_load` is applied every time it loads.

Time of day uses Minecraft's numbers: 0 = sunrise, 1000 = morning, 6000 = noon, 12000 = sunset, 13000 = night, 18000 = midnight.

Time stopped at noon:

```json
{
  "state": {
    "initial": {
      "day_time": 6000,
      "gamerules": { "doDaylightCycle": false }
    }
  }
}
```

Custom day and night lengths, in ticks (20 ticks = 1 second; a normal day and night are 12000 each):

```json
{
  "state": {},
  "time": {
    "day_length": 36000,
    "night_length": 6000,
    "celestial": "cycle"
  }
}
```

`celestial` is `native` (Minecraft's sun movement, default), `cycle` (JED's day/night formula, which follows the lengths above) or `range` (sun moves only within `"angle_range": { "min": 0.2, "max": 0.8 }`, as fractions of a full turn). To make the clock speed itself faster or slower, add `"on_load": { "day_time_per_tick": 0.5 }` inside `state`: 0.5 is half speed, 2 is double speed, 0 stops it.

### Step 6: Weather

Choices:

1. **Same as the Overworld** (default)
2. **Always clear.** Needs `state`.
3. **Always raining.** Needs `state`.
4. **Its own normal weather.** Just `"state": {}`.

Always clear:

```json
{
  "state": {
    "initial": {
      "gamerules": { "doWeatherCycle": false },
      "weather": { "raining": false, "thundering": false }
    }
  }
}
```

Always raining:

```json
{
  "state": {
    "initial": {
      "gamerules": { "doWeatherCycle": false },
      "weather": { "raining": true, "thundering": false, "rain_time": 2400000, "clear_time": 0 }
    }
  }
}
```

Weather extras, all optional:

```json
{
  "weather": {
    "natural_lightning": false,
    "snow": "ignore_temperature",
    "ice": "disabled",
    "preserve_on_sleep": true
  }
}
```

| Field                         | Values                                                                                                             |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `natural_lightning`           | `false` stops natural lightning strikes.                                                                           |
| `snow`, `ice`                 | `native` (follow the biome's temperature), `disabled` (never), `ignore_temperature` (snow or freeze in any biome). |
| `preserve_on_sleep`           | `true` keeps the rain going when players sleep.                                                                    |
| `precipitation_block_effects` | `false` stops rain filling cauldrons and similar effects.                                                          |

### Step 7: Difficulty, game mode and game rules

All of these need `state`. Ask for:

- **Difficulty:** `peaceful`, `easy`, `normal` or `hard`.
- **Game mode:** `survival`, `creative`, `adventure` or `spectator`. To make it apply to everyone who enters (and switch back when they leave), also set `"force_game_mode": true` in `player` (step 9).
- **Game rules:** any Minecraft game rule, using its exact name and a `true`/`false` or number value. Common ones: `keepInventory`, `doMobSpawning`, `doFireTick`, `mobGriefing`, `naturalRegeneration`, `doImmediateRespawn`, `fallDamage`, `doDaylightCycle`, `doWeatherCycle`, `randomTickSpeed`.

```json
{
  "state": {
    "initial": {
      "difficulty": "hard",
      "default_game_mode": "adventure",
      "gamerules": { "keepInventory": true, "mobGriefing": false }
    }
  }
}
```

When several steps use `state`, **merge them into one `state` section**, with one `initial` holding all the values.

### Step 8: Mobs

Choices:

1. **Normal** (default). Write nothing.
2. **No hostile mobs** spawn naturally.
3. **No mobs at all** spawn naturally.
4. **More or fewer animals.**

```json
{
  "mobs": {
    "hostile": false,
    "peaceful": true
  }
}
```

- `hostile`: monsters. `peaceful`: animals, bats and fish. `false` stops them spawning naturally; spawners, spawn eggs and breeding still work.
- `creature_cap`: an optional whole number that replaces the normal animal limit (normally 10 per player area); 0 stops new animals spawning.
- Peaceful difficulty already stops monsters, and the `doMobSpawning` game rule stops all natural spawning.
- Finer control (banning specific mobs, adding mobs, caps per player) uses a separate mob policy file, which this guide doesn't cover.

### Step 9: Inventory and starting items

Choices:

1. **Same inventory as the rest of the server** (default). Write nothing.
2. **A separate inventory** while in this dimension. Players get their normal inventory back when they leave. Dimensions with the same group name share one inventory. The name is case-sensitive.
3. **A starting kit** the first time someone enters.

```json
{
  "player": {
    "inventory_group": "Creative",
    "force_game_mode": true
  }
}
```

A separate group is strongly recommended for creative dimensions, so creative items can't be carried into survival.

For a starting kit, write a kit file at `config/justepicdimensions/data/<namespace>/justepicdimensions/loadouts/<kit>.json`. Containers are `hotbar` (slots 0 to 8), `armor` (0 = boots, 1 = leggings, 2 = chestplate, 3 = helmet) and `offhand` (0):

```json
{
  "format_version": 1,
  "containers": [
    {
      "backend": "minecraft",
      "container": "hotbar",
      "slots": [
        { "index": 0, "item": { "id": "minecraft:stone_pickaxe", "count": 1 } },
        { "index": 1, "item": { "id": "minecraft:bread", "count": 8 } }
      ]
    },
    {
      "backend": "minecraft",
      "container": "armor",
      "slots": [
        { "index": 3, "item": { "id": "minecraft:leather_helmet", "count": 1 } }
      ]
    }
  ]
}
```

Then reference it from the properties:

```json
{
  "player": {
    "inventory_group": "Adventure",
    "loadouts": {
      "first_entry": { "kit": "mypack:starter_kit", "mode": "fill_empty" }
    }
  }
}
```

- `first_entry` gives the kit once per player. Optional `"scope": "dimension"` gives it once per dimension instead of once per inventory group.
- `respawn` gives it after each death in the dimension. `every_entry` gives it on every visit.
- `mode` is `fill_empty` (fill empty slots only, default), `top_up` (top stacks up to the count), `replace_slots` (overwrite the listed slots) or `replace_containers` (clear the listed containers first).

### Step 10: Sleeping and respawning

Choices for beds (`sleep.mode`):

1. **Normal** (default)
2. **Allow sleeping** (`allow`)
3. **No sleeping** (`deny`)
4. **Beds explode** (`explode`)

Choices for where players respawn after dying here:

1. **Normal** (default): their bed, or the Overworld spawn.
2. **In this dimension** (`"allow_here": true`)
3. **In a specific dimension** (`"destination": "<dimension ID>"`)

```json
{
  "sleep": { "mode": "deny", "bed_checkpoints": false },
  "respawn": { "allow_here": true }
}
```

`bed_checkpoints` controls whether clicking a bed sets the respawn point. `respawn.priority` is `destination_first` (default) or `saved_point_first`, which uses a player's bed anywhere before the configured destination.

### Step 11: Look and sound

All optional. Colours are `"#RRGGBB"`. Ask which they want to change:

```json
{
  "render": {
    "sky": {
      "color": "#4080FF",
      "biome_blend": 1,
      "sun": { "scale": 1.5, "color": "#FFCC80" },
      "moon": { "visible": true, "scale": 0.8 },
      "stars": true,
      "sunrise": true
    },
    "clouds": { "visible": true, "height": 160, "color": "#FFFFFF" },
    "fog": { "color": "#8899AA", "near": 16, "far": 96 },
    "biome_colors": {
      "grass": [ { "tag": "minecraft:is_overworld", "color": "#80AA50" } ],
      "foliage": [ { "tag": "minecraft:is_overworld", "color": "#558833" } ],
      "water": [ { "tag": "minecraft:is_overworld", "color": "#3F76E4" } ]
    },
    "music": { "mode": "preset", "preset": "creative" }
  }
}
```

| Field                               | Values                                                                                                                                                                                                           |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `sky.mode`                          | `inherit` (default), `overworld`, `end` (End-style sky) or `none`.                                                                                                                                               |
| `sky.color` with `"biome_blend": 1` | Use exactly this sky colour. `"fixed": true` also stops it changing at night or in rain.                                                                                                                         |
| `sky.sun`, `sky.moon`               | `visible`, `scale` (0 to 16), `color`.                                                                                                                                                                           |
| `sky.stars`, `sky.sunrise`          | `true` or `false`.                                                                                                                                                                                               |
| `clouds`                            | `visible`, `height`, `color`, `fixed`.                                                                                                                                                                           |
| `fog`                               | `color`, `near` and `far` (blocks), `shape` (`sphere` or `cylinder`), `fixed`.                                                                                                                                   |
| `biome_colors`                      | Lists for `grass`, `foliage` and `water`. Each entry has one of `biome` (a biome ID), `tag` (a biome tag, e.g. `minecraft:is_overworld` for all Overworld biomes) or `regex`, plus a `color`. Later entries win. |
| `music.mode`                        | `native` (default), `silent`, or `preset` with `preset` = `game`, `creative`, `nether`, `end`, `end_boss`, `under_water`, `menu` or `credits`.                                                                   |

**Darker or brighter overall:** a brightness table in `lighting` sets how bright each light level (0 to 15) looks, with exactly 16 numbers. This one makes dark areas darker:

```json
{
  "lighting": {
    "brightness_table": [0.0, 0.01, 0.02, 0.04, 0.06, 0.09, 0.13, 0.18, 0.24, 0.31, 0.39, 0.48, 0.58, 0.70, 0.84, 1.0]
  }
}
```

### Step 12: Travel rules and world border

Who may come and go, what happens at the top or bottom of the world, and how big the world is. All optional:

```json
{
  "travel": {
    "allow_entry": true,
    "allow_exit": true,
    "void": {
      "dimension": "minecraft:overworld",
      "trigger_y": -80,
      "mode": "spawn",
      "remove_fall_damage": true
    }
  }
}
```

- `allow_entry: false` blocks travelling in (operators can still use `/tpj bypass`). `allow_exit: false` blocks leaving.
- **Fall into the void** (`void`): anything that falls below `trigger_y` is sent to `dimension`. Set `trigger_y` 16 below the bottom of the world: −80 for the Overworld types, −16 for Nether and End types. This is recommended for void and floating-island worlds.
- **Fly out the top** (`sky`): the same fields, triggered above `trigger_y`.
- `mode` decides where they land: `spawn` (the destination's arrival point), `same` (same X, Y, Z), `fixed` (with `"position": [x, y, z]`), or `scaled`/`relative`/`scaled_relative`. Add `"surface": true` to land on the ground at that X and Z.
- `passengers`: `dismount` (default) or `together` (ride along with a mount).

**World border:** the size goes in `state` (needs `state`), and `border` centres it on the arrival point:

```json
{
  "state": {
    "initial": {
      "border": { "size": 2000 }
    }
  },
  "border": { "center_on_spawn": true }
}
```

`size` is the full width in blocks, so 2000 means 1000 blocks each way from the centre.

### Step 13: Loading, seed and extras

All optional. Ask only if the user seems interested, or list them briefly.

**Loading** (`lifecycle`). Without this section, when the dimension opens depends on how it was added. Added with `/jed create` while the server runs: it opens when someone first goes there. Its files already in place when the server started: it opens with the server at every start, like the Nether. Either way it then stays open until the server stops, unless the server owner has turned on automatic unloading for all dimensions. Writing a `lifecycle` section makes it behave the same both ways.

```json
{
  "lifecycle": {
    "load_on_start": false,
    "keep_loaded": false,
    "automatic_unload": true,
    "idle_seconds": 300
  }
}
```

- `load_on_start: true` opens it when the server starts.
- `keep_loaded: true` keeps it, and its spawn area, always loaded (uses more memory).
- `automatic_unload: true` closes it after `idle_seconds` with nobody in it, which saves memory.
- `temporary: true` **wipes the dimension** every time it unloads (for minigame arenas). Warn the user clearly before using it, and pair it with `"automatic_unload": true` and a `"fallback": "minecraft:overworld"` dimension for players.

**Seed and structures** (`generation`):

```json
{
  "generation": {
    "seed": "any text or a number",
    "structures": true
  }
}
```

`seed` is a whole number, any text, or `"random"` (picked once and saved). Leave it out to use the world's seed. `structures: false` turns off villages, temples and other structures.

**End dragon** (End-style terrain only):

```json
{
  "end": {
    "dragon_fight": true,
    "generate_spikes": true,
    "reconstruct_spikes": true
  }
}
```

`generate_spikes: false` removes the obsidian pillars.

**Entry requirement** (players need an item, advancement, team or score to enter). Write a rule file at `config/justepicdimensions/data/<namespace>/justepicdimensions/access_rules/<rule>.json`:

```json
{
  "format_version": 1,
  "condition": {
    "item": { "predicate": { "items": "minecraft:tripwire_hook" }, "min": 1 }
  }
}
```

and reference it:

```json
{
  "access": {
    "rule": "mypack:entry_key",
    "visibility": "locked",
    "feedback": "requirements"
  }
}
```

Other conditions include `{ "advancement": { "id": "minecraft:story/enter_the_nether" } }` and `{ "team": "explorers" }`. Combine them with `{ "all": [ … ] }`, `{ "any": [ … ] }` or `{ "not": { … } }`. `visibility` is `locked` (shown with a lock) or `hidden` (not listed for players who can't enter). Scores, permissions and other conditions exist but are not covered here.

**Harmful environment** (for example poison air unless wearing a turtle helmet). Write a policy at `config/justepicdimensions/data/<namespace>/justepicdimensions/environment/<policy>.json`:

```json
{
  "format_version": 1,
  "rules": [
    {
      "id": "toxic_air",
      "effects": [ { "id": "minecraft:poison", "duration": 60 } ],
      "protection": {
        "items": [
          { "predicate": { "items": "minecraft:turtle_helmet" }, "slots": ["minecraft:head"], "immune": true }
        ]
      }
    }
  ]
}
```

and reference it:

```json
{
  "environment": { "policy": "mypack:toxic_air" }
}
```

Damage, healing and timing have more settings that this guide doesn't cover.

**Beyond this guide.** These exist but this guide doesn't cover them. Tell the user they exist rather than writing them from memory:

- Private copies per player or team
- Scheduled resets or opening hours
- Pre-generating terrain
- Shared setting bundles reused across dimensions
- Separate health, XP or hunger per dimension
- Custom structures that generate repeatedly

### Step 14: Review and write

1. Summarise the user's choices in a short list and ask them to confirm.
2. Merge all chosen properties sections into **one** properties file: one `state`, one `player` and so on. Start it with `"format_version": 1`.
3. Run the [checks](#checks-before-answering).
4. Output each file with its full path, then the [activation steps](#activating-the-dimension).

---

## Providers

A provider gives a dimension the matching built-in behaviour: compasses and clocks for surface worlds, Nether rules, or End portals and dragon support. Add the provider that matches the terrain:

| Terrain                                       | Provider                     |
| --------------------------------------------- | ---------------------------- |
| Overworld-style, flat, void, floating islands | `justepicdimensions:surface` |
| Caves                                         | `justepicdimensions:base`    |
| Nether-style                                  | `justepicdimensions:nether`  |
| End-style                                     | `justepicdimensions:end`     |

```json
{
  "provider": { "type": "justepicdimensions:surface" }
}
```

Optional `"settings"` inside `provider`: `surface_world` (compass and clock behaviour), `water_vaporizes`, `spawn_height`, `fog` (`true`/`false`) and `horizon` (horizon height).

---

## Checks before answering

- [ ] The dimension file and properties file use the same `<namespace>/<name>`, and the properties file is in `…/justepicdimensions/dimension_properties/`.
- [ ] The properties file starts with `"format_version": 1` and has no `id` field.
- [ ] Every section and field appears in this guide, with a listed value. No comments, no trailing commas, no duplicate keys.
- [ ] There is only one `state`, one `player`, one `render` and so on. Values from several steps are merged.
- [ ] Anything about time, weather, difficulty, game mode, game rules or border size is inside `state`.
- [ ] `force_game_mode: true` comes with a `default_game_mode` in `state.initial`.
- [ ] Flat worlds use a real biome ID, and their layers are listed bottom to top.
- [ ] Void worlds have a `fixed` arrival point with `fallback_block: true` or a spawn structure, plus a void travel rule.
- [ ] Cave and Nether-style worlds use the `cavern` arrival mode.
- [ ] A custom dimension type's `min_y` and `height` match the terrain (−64/384 or 0/256).
- [ ] Every ID that refers to another file (kit, access rule, environment policy, custom type) uses the user's namespace and matches that file's path.
- [ ] Colours are `#RRGGBB`. Brightness tables have exactly 16 numbers.

---

## Activating the dimension

Tell the user:

1. **Save the files** at the paths shown. Create any folders that don't exist yet.
2. **Create the dimension.** As an operator (or with cheats on in single player), run `/jed create <namespace>:<name>`, or `jed create <namespace>:<name>` from the server console. It replies that it is preparing, then confirms. No restart is needed. Both ways of adding a dimension work, so there's no need to time it: save the files while the server runs and use `/jed create`, or save them while it's stopped and the server creates the dimension when it starts. In the second case `/jed create` replies "Dimension is already registered", which is fine, so go straight to step 3. Without a `lifecycle` section, the two differ only in when the dimension opens (see step 13).
3. **Visit it:** `/tpj <namespace>:<name>`. Return with `/tpj minecraft:overworld`. `/tpj list` shows every dimension.
4. **Change it later:**
   - Edits to the **properties** file: run `/jed reload`. `state.initial` values and the saved arrival point don't change for a dimension that already exists. Use `state.on_load` for values that should apply every time it loads, or the `/jed time`, `/jed gamerule` and `/jed setworldspawn` commands.
   - Edits to the **dimension** or **dimension type** file: take effect after a server restart, and only in areas not explored yet. For a completely fresh world, use `/jed reset <namespace>:<name>`, which moves the old world files to JED's trash.
5. **If something is wrong**, the command reply and the server log name the file and the field. Fix it and run the command again.

---

## Complete examples

These show how the steps combine. They use the example namespace `mypack`; use the user's own.

### A peaceful creative building world

Flat grass at Y 64, always noon, clear skies, no mobs, creative mode with its own inventory.

`config/justepicdimensions/data/mypack/dimension/build_world.json`

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:plains",
      "layers": [
        { "block": "minecraft:bedrock", "height": 1 },
        { "block": "minecraft:stone", "height": 124 },
        { "block": "minecraft:dirt", "height": 3 },
        { "block": "minecraft:grass_block", "height": 1 }
      ],
      "lakes": false,
      "features": false,
      "structure_overrides": []
    }
  }
}
```

`config/justepicdimensions/data/mypack/justepicdimensions/dimension_properties/build_world.json`

```json
{
  "format_version": 1,
  "display_name": "Build World",
  "provider": { "type": "justepicdimensions:surface" },
  "state": {
    "initial": {
      "day_time": 6000,
      "difficulty": "peaceful",
      "default_game_mode": "creative",
      "gamerules": {
        "doDaylightCycle": false,
        "doWeatherCycle": false,
        "doMobSpawning": false
      },
      "weather": { "raining": false, "thundering": false }
    }
  },
  "spawn": {
    "mode": "surface",
    "reset_fall_distance": true,
    "reset_velocity": true
  },
  "player": {
    "inventory_group": "Creative",
    "force_game_mode": true
  },
  "mobs": { "hostile": false, "peaceful": false }
}
```

### A wilderness with long days

A hand-picked biome mix, 30-minute days with 5-minute nights, no lightning, a 4000-block border and hard difficulty. Players keep their normal inventory.

`config/justepicdimensions/data/mypack/dimension/long_days.json`

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:overworld",
    "biome_source": {
      "type": "justepicdimensions:weighted_regions",
      "region_size": 256,
      "shape": "irregular",
      "entries": [
        { "biome": "minecraft:flower_forest", "weight": 3 },
        { "biome": "minecraft:meadow", "weight": 2 },
        { "biome": "minecraft:birch_forest", "weight": 2 },
        { "biome": "minecraft:cherry_grove", "weight": 1 }
      ]
    }
  }
}
```

`config/justepicdimensions/data/mypack/justepicdimensions/dimension_properties/long_days.json`

```json
{
  "format_version": 1,
  "display_name": "Long Days",
  "provider": { "type": "justepicdimensions:surface" },
  "state": {
    "initial": {
      "day_time": 1000,
      "difficulty": "hard",
      "border": { "size": 4000 }
    }
  },
  "time": {
    "day_length": 36000,
    "night_length": 6000,
    "celestial": "cycle"
  },
  "weather": { "natural_lightning": false },
  "spawn": {
    "mode": "surface",
    "reset_fall_distance": true,
    "reset_velocity": true
  },
  "border": { "center_on_spawn": true },
  "generation": { "seed": "long days", "structures": true }
}
```

### A void world for a skyblock-style start

Empty, with one glass block to stand on at 0, 64, 0. Falling off returns players to the Overworld spawn without fall damage. Survival mode, with a separate inventory and a small starting kit.

`config/justepicdimensions/data/mypack/dimension/skyblock.json`

```json
{
  "type": "minecraft:overworld",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "biome": "minecraft:the_void",
      "layers": [],
      "lakes": false,
      "features": false,
      "structure_overrides": []
    }
  }
}
```

`config/justepicdimensions/data/mypack/justepicdimensions/loadouts/skyblock_start.json`

```json
{
  "format_version": 1,
  "containers": [
    {
      "backend": "minecraft",
      "container": "hotbar",
      "slots": [
        { "index": 0, "item": { "id": "minecraft:oak_sapling", "count": 2 } },
        { "index": 1, "item": { "id": "minecraft:dirt", "count": 16 } },
        { "index": 2, "item": { "id": "minecraft:water_bucket", "count": 1 } },
        { "index": 3, "item": { "id": "minecraft:lava_bucket", "count": 1 } }
      ]
    }
  ]
}
```

`config/justepicdimensions/data/mypack/justepicdimensions/dimension_properties/skyblock.json`

```json
{
  "format_version": 1,
  "display_name": "Skyblock",
  "provider": { "type": "justepicdimensions:surface" },
  "state": {
    "initial": {
      "default_game_mode": "survival",
      "difficulty": "normal"
    }
  },
  "spawn": {
    "mode": "fixed",
    "x": 0,
    "y": 64,
    "z": 0,
    "fallback_block": true,
    "reset_fall_distance": true,
    "reset_velocity": true
  },
  "player": {
    "inventory_group": "Skyblock",
    "force_game_mode": true,
    "loadouts": {
      "first_entry": { "kit": "mypack:skyblock_start", "mode": "fill_empty" }
    }
  },
  "travel": {
    "void": {
      "dimension": "minecraft:overworld",
      "trigger_y": -80,
      "mode": "spawn",
      "remove_fall_damage": true
    }
  }
}
```

### A dark cave world

Lush and dripstone caves under a stone ceiling, darker than normal, with hostile mobs, hard difficulty and dark fog. Players respawn inside it.

`config/justepicdimensions/data/mypack/dimension/deep_caves.json`

```json
{
  "type": "minecraft:overworld_caves",
  "generator": {
    "type": "minecraft:noise",
    "settings": "minecraft:caves",
    "biome_source": {
      "type": "justepicdimensions:weighted_regions",
      "region_size": 128,
      "shape": "irregular",
      "entries": [
        { "biome": "minecraft:lush_caves", "weight": 1 },
        { "biome": "minecraft:dripstone_caves", "weight": 1 }
      ]
    }
  }
}
```

`config/justepicdimensions/data/mypack/justepicdimensions/dimension_properties/deep_caves.json`

```json
{
  "format_version": 1,
  "display_name": "Deep Caves",
  "provider": { "type": "justepicdimensions:base" },
  "state": {
    "initial": { "difficulty": "hard" }
  },
  "spawn": {
    "mode": "cavern",
    "reset_fall_distance": true,
    "reset_velocity": true
  },
  "respawn": { "allow_here": true },
  "mobs": { "hostile": true, "peaceful": false },
  "lighting": {
    "brightness_table": [0.0, 0.01, 0.02, 0.04, 0.06, 0.09, 0.13, 0.18, 0.24, 0.31, 0.39, 0.48, 0.58, 0.70, 0.84, 1.0]
  },
  "render": {
    "fog": { "color": "#101418", "near": 8, "far": 64 },
    "music": { "mode": "preset", "preset": "under_water" }
  }
}
```

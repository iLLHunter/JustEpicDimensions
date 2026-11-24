# Just Epic Dimensions playable showcase

Eleven destinations demonstrate rendering, world generation, access, independent player state, natural spawning, environmental protection, instances and lifecycle controls. Uses Minecraft 1.21.1, NeoForge 21.1.243 or newer compatible 21.1 releases, and matching Just Epic Dimensions / Arbitrage builds. The base tour uses vanilla assets. Chunky is optional.

## Enable the bundled tour

In `config/justepicdimensions/justepicdimensions.toml` set:

```toml
useShowcase = true
```

The default is `false`. Open/reopen the world or restart the server. Before native dimension discovery, JED unpacks missing bundled files into `config/justepicdimensions/data/`, creating the directory tree as needed. Existing JSON, structures and unrelated files are preserved. A later startup fills missing files without replacing edits. Setting the option back to false stops automatic installation; it does not remove installed dimensions or their saved worlds.

The existing global/per-save source and copy switches retain their behavior. A fresh save can receive the tour through ordinary configuration copying. For an existing save that selects its own dimension configuration, merge the showcase data into `<save>/serverconfig/justepicdimensions/data/` or select the global source; enabling this option does not replace an existing per-save configuration. Worldgen changes affect newly generated chunks and retain normal restart/lifecycle requirements.

Enter `/tpj justepicdimensions:showcase_hub spawn`. Initial player placement and command permissions remain unchanged: this is an operator-led test tour. The hub contains a tour book, labelled stations, supplies and Arena Keys. Book links suggest commands without executing them or granting permissions. Existing main configurations must retain the values in `required-main-settings.toml` for the corresponding demonstrations.

Destination stations use adjacent signs, read from left to right, with complete sentences describing the world, its sights and its rules. Long-Day Wilderness uses two signs; the other destinations and monitoring station use three. Normal signs allow four lines of 90 pixels each, so there is no fixed character count: wide letters take more room than narrow ones. The generator wraps words within that width. These authored signs currently contain literal US English text; they do not use translation keys and do not change with the client's language.

The tour book is the registered item `justepicdimensions:guide_book`. The normal global inventory policy retains it by default; the showcase has no separate retention exception. If you already have a custom `globalInventoryItems` list, add this ID to that list. An existing explicit empty list disables retention. `/give @s justepicdimensions:guide_book` provides the current guide without rebuilding the hub; older vanilla-book copies keep their original item ID.

The tour book opens with a welcome, then gives each world a short description, visible sights, other features and prepared travel/action links. Clicking a command link opens chat with the command ready; press Enter to use it. The book remembers each player's reading position until logout, including after dimension travel. This works with Minecraft's reader, Scholar's two-page reader and Scribble 1.5.1's adjusted native reader. Whole paragraphs are kept together across the native book pages.

For an existing installation, replace the extracted showcase data with the updated archive's `config/justepicdimensions/data/` contents in the selected data source, and install the matching JED JAR. Fully restart the game/server. Do not use `/reload` in FTB Evolution. Already placed structures, signs and existing book items must be regenerated or replaced; `useShowcase` preserves existing extracted files and saved blocks/items.

Both arenas now select the terrain surface and raise their spawn three blocks. A roofed sandstone shelter has a pressure plate inside the iron door, a button outside, and a railed staircase to the combat floor and back. This also applies to newly created personal/team instances. All showcase world-spawn travel clears incoming velocity and fall distance, without ongoing damage immunity. Existing arena worlds retain their saved spawn and structures until reset.

Both quarries also select the actual terrain surface rather than assuming Y=65. Their raised stone station has a railed staircase down to the ground and back. This remains aligned when a pack changes the world's minimum build height. Replace both quarry property files and `showcase_quarry_station.nbt`, then reset their existing worlds to replace saved spawns and platforms. Meadow pavilions have two signs each; the old sheep-limit, MPS cold-rule and arena stair-entrance signs have been removed. Existing placed signs require regeneration or manual removal. For the revised guide, collect a fresh book from a regenerated hub or use `/give @s justepicdimensions:guide_book` after installing the updated JAR.

The pavilion's left sign names its biomes: Flower Forest and Meadow for birch, Cherry Grove for cherry. Its other sign reports the minimum 80-block distance between placement centers. Both structure sets share the same random-spread grid and salt: spacing 10 and separation 4 allow offsets 0–5, giving a minimum adjacent-center gap of five chunks (80 blocks). This is a center distance, not a clear gap between structure edges. Quarry stations retain only the loot/reset notice; the stairs instruction sign is removed.

An existing tour book in the player's ordinary inventory or offhand travels with them when entering, leaving or moving between showcase worlds and their personal/team instances. Arena kits still load and gear changes still save separately. The carried books are removed from the departing inventory snapshot, so returning does not create extra copies. Ordinary books follow normal inventory separation. If the destination cannot fit the incoming tour book, travel stops before the swap and asks the player to free space. This does not grant a book to players without one or change normal death, grave or command-permission rules.

For a full saved arena inventory, store the book in a chest temporarily, enter and free a slot, then collect the book and retry. Updating JED enables the book transfer for existing marked tour books; updating/regenerating the hub supplies the new book directions, and regenerating the huts adds the plates.

To refresh the hub through JED's reset command, leave it first. While the game/server is stopped, set `lifecycle.keep_loaded` to `false` in the selected `justepicdimensions/dimension_properties/showcase_hub.json`. Restart, then use `/jed reset justepicdimensions:showcase_hub relocate`. Existing instance worlds use their instance reset tools. Resetting replaces builds in that dimension, so retain a world backup. Restore the hub's `keep_loaded` setting afterward if you want its normal retained behavior.

Manual installation still works: merge the contents of `config/` from the standalone showcase archive. `showcase.json` is a human-readable index, not a loadable definition.

## Destinations and exercises

| Destination | Try it | Features |
| --- | --- | --- |
| Showcase Hub | Read the tour book and labelled stations. Leave iron smelting, visit another world, then return. | Centered native structure, block entities, retained ticking spawn chunks, forced Creative and independent border/time. |
| Prismatic Meadow | Explore flower forests, meadows and cherry groves. The open pavilions have outdoor campfires, smokers, workstations and watered potato/carrot beds; inspect the worldgen biome preview. | Weighted distribution, biome-restricted authored structures with spacing 10/separation 4, pastel tints, repeating afternoon and sky return above Y=224. |
| Crimson Nightmare | Compare its red grass, black foliage, violet water, huge red sun, green clouds/haze and purple sky with the meadow. | Existing extreme appearance demonstration; no new hazards or hostile spawning. Its paused clock remains 11000. |
| Long-Day Wilderness | Observe natural wildlife from the signed deck; inspect the policy in the builder. | Sheep weight x3, 240-loaded/48-within-48-blocks sheep caps, added rabbit entries, independent seed and 36000/6000-tick day/night. |
| Frost Garden | Take bread and armor before stepping off the lodge's roofed area. Compare no gear, boots, leggings, then chestplate. | Exposed snowfall applies Slowness and half a heart of direct freezing damage every 40 ticks before normal defenses. Vanilla and MPS Power Armor boots reduce direct damage 25%, leggings 50%; strongest reduction wins. Their chestplates suppress the whole rule. |
| Lantern Cavern | Explore mossy slopes, pools, glow-berry canopies and hanging ruins. Compare lit gardens and shadowed stone. Inspect biome IDs below/above Y=80. Try the checkpoint bed. | Native noise-shaped cavern, lush decoration and floor/ceiling pieces; lower lush/upper dripstone bands, no skylight, brightness table, checkpoint without sleep and scaled void return. |
| Loadout Arena | First try entry without a key, then carry a hub tripwire-hook key. Change arena XP, health, food and effects; leave and return. | Locked listing/access feedback, independent inventory and five state categories, curated/capped hostile natural spawning, first-entry/respawn kits and personal instances. |
| Team Loadout Arena | Operators create a scoreboard team and its instance, then compare authorized runs. | Current team membership, secure membership revocation and per-player/per-instance state. |
| Temporary Quarry | Mine and carry resources out; revisit after eligible idle closure. | Original temporary-world behavior: disposable terrain, 30-second eligible idle reset, shutdown cleanup and shared travelling inventory. |
| Scheduled Quarry | Mine, leave a marker, wait for the 15-minute deadline and empty the world. Inspect the reset/pregeneration status. | Separate non-temporary world; recurring real-time reset, deferred while occupied with ordinary entry blocked, fallback relocation and seven-day recoverable history. Ordinary 90-second idle unloading saves terrain. |
| Pillarless End | Observe the independent dragon fight and crystal resummoning. | Existing independent End with spike generation/reconstruction disabled, Creative observation and native End return. |

Return with `/tpj justepicdimensions:showcase_hub spawn` or `/tpj minecraft:overworld spawn`. Only the hub is kept loaded. The other permanent destinations may save/unload after 90 eligible idle seconds. Tickets and active operations can postpone unloading.

### Arena state exercise

Take an Arena Key (tripwire hook) from the hub. Ordinary entry checks the departing inventory before swapping to the arena inventory. Entry requirements are not consumed; respawn/reconnect retain the existing allow-by-default behavior.

The first arena visit starts with level 5, two absorption hearts, fresh food, a clean ordinary-effect group and the existing starter loadout. Health uses absolute restoration and a temporary ten-heart cap that preserves normal/modded permanent capacity outside the group. Change values, leave and return to see saved arena state restored. Ordinary source state returns when leaving. Same-group death follows native/modded death results; it does not resurrect a pre-death snapshot. Paused effects resume when returning to their group.

Natural arena mobs are zombies, skeletons and spiders, with 18 combined loaded / 8 within each nearby player's 48-block radius; zombie weights double. Native placement, light and spawning checks remain authoritative. Existing mobs are not culled, and deliberate eggs/commands are outside these natural-only rules.

### Personal and team instances

Operators use:

```mcfunction
/tpj instance create justepicdimensions:showcase_arena
/tpj instance enter <returned-instance-id>
/tpj instance exit
/team add showcase_blue
/team join showcase_blue <player>
/tpj instance create justepicdimensions:showcase_team_arena
```

Enter the returned team instance ID in the same way. Both sources retain operator-only creation/management, explicit entry and one persistent run per owner. Each configured state category, including inventory, is scoped per instance and per player. Place a marker, exit and compare with another authorized instance. Team membership is always current; revoked occupants are held and safely evacuated, with disconnect if no safe transfer is possible. Personal/team source dimensions are demonstrations too; instancing happens only through the explicit commands.

### Optional Chunky preparation

With `useShowcase=true` and Chunky installed, JED automatically adds the scheduled quarry's preparation binding in memory. Installed JSON is not rewritten. An explicit `pregeneration` field in that dimension's properties takes precedence. Without Chunky the quarry stays usable and scheduled resets still run.

Preparation covers a bounded 128-block-radius square, starts initially and after successful resets, blocks ordinary arrivals until complete, pauses below 18 TPS and resumes after ten seconds at/above 19 TPS. Reconnects, respawns and initial spawns retain their approved exemptions. Use `/jed pregen status justepicdimensions:showcase_scheduled_quarry`. A manually installed tour with `useShowcase=false` can reference `justepicdimensions:showcase_quarry` through the normal pregeneration editor when desired.

### Operator monitoring

At the hub's operator station, open `/jed gui` â†’ World tools â†’ Dimension monitoring. **Open dashboard** opens the authenticated client-linked browser window for a second monitor. The hub explicitly enables five-second history with the normal seven-day retention and hourly/manual disk refresh; global monitoring remains unchanged. Current diagnostics for the other dimensions remain available without enabling their history. No artificial lag, disk-filling workload or automatic monitoring action is configured.

### Existing details retained

Frost Garden accepts leather, chainmail, gold, iron, diamond and netherite boots, leggings and chestplates for its special cold protection. When MPS Re-modularized is installed, Primitive, Prototype, Prototype MkII and Power Armor pieces follow the same matching-slot rules, with no charge or installed-module requirement. Helmets provide no special cold protection. The supplied leather gear is enough; carrying armor without wearing it provides no protection. Its chestplate immunity applies to the showcase cold rule, not all damage. Frost Garden forces Survival for the exposure exercise. Its bed still allows sleep without clearing snowfall; its paused night does not advance to dawn. The roof stops exposure refreshes; remaining Slowness expires normally. Snow accumulation remains native; water freezing is independently disabled.

The three `justepicdimensions:showcase_cold_*` item tags are native resources in the JED JAR. MPS entries are optional, so the base tour works without MPS. Pack authors can extend those tags through an ordinary datapack.

Lantern Cavern uses `sleep.mode=deny` and `bed_checkpoints=true`: the bed saves a checkpoint but cannot start sleep. Void travel below Y=-4 returns to the hub at native 8:1 scaling. Prismatic Meadow retains passenger-aware sky return above Y=224. Nightmare and End retain their earlier visual/gameplay designs.

Appearance depends on native client settings: Clouds must be enabled for cloud demonstrations and `enableColorOverrides=true` for biome tints. Resource/shader packs, gamma and lighting affect the final pixels. The saved Nightmare previews predate its late-afternoon clock adjustment.

Both quarries are explicitly disposable. The temporary quarry resets on eligible closing; the scheduled quarry resets only through its recurring/manual lifecycle and keeps ordinary unloaded terrain. Carry resources out of containers before a reset. No installed modpack or player save is modified by the authoring/testing scripts.

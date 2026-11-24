# Expanded showcase validation

## Pavilion and quarry sign wording — 2026-11-07

Applied the requested capitalized biome names and exact four-line wording. Pavilion biome signs occupy local X=4 and spacing signs X=3, placing the biome sign on the left when read from the north-facing side shown in the screenshots. The spacing value is 80 blocks between candidate centers: native random-spread offsets are 0–5 for spacing 10/separation 4, and authored generation anchors at chunk centers. Both pavilion sets share that grid/salt. Removed the quarry stairs sign and replaced its main sign with the requested loot/reset text. All generated lines fit the 90-pixel bound. Decoded NBT comparison confirmed only sign blocks/text changed across the three assets; exact messages/order and the single remaining quarry sign were checked. All 28 showcase installer contracts passed. This sign-only pass did not repeat graphical gameplay tests.

## Sign, guide and quarry arrival corrections — 2026-11-07

Updated both pavilion types to two signs with complete sentences. Removed the requested Long-Day platform limits sign, Frost MPS sign, arena stair-entrance sign and final Long-Day hub sign. The hub now has 33 signs; Long-Day's hub station has two. The sheep policy now limits 240 loaded sheep dimension-wide and 48 within 48 blocks of each player. Guide wording follows the requested revisions and regenerates into 48 whole-paragraph pages.

Both quarries now use a surface search with a three-block spawn offset and a shared stone station with railed stairs. The previous fixed Y=65 assumption could leave players/platforms above the terrain when a pack lowers the dimension floor. `showcase-corrections-20261107-01` passed **270 installed dedicated-server checks** in 100.26 seconds, including native travel with incoming fall distance/motion, solid arrivals and native collision walks down/up both quarry staircases without health loss. This fixture set the minimum build height to -128: both quarry spawns correctly resolved to Y=4 above the actual ground, rather than Y=65. Arena and other showcase regressions remained included.

`showcase-corrections-scholar-20261107-02` passed **1,134 real-client checks** with Scholar: 48 pages, 376 sign lines, 25 command links, native mouse dispatch, retained-book arena travel, door controls, hub/Overworld returns and session bookmarks. Rendered revised Frost pages were inspected. The initial graphical fixture omitted its synthetic dimension type when copying the lowered-floor server world; the launcher now carries those test definitions too. Superseded client trial 01 is not acceptance evidence. The final server and client closed normally.

Static NBT/content checks confirmed the requested sign removals/counts, front/back line-width bounds, exact sheep limits and guide edits. All **28 showcase installer checks** passed. JED SHA-256: `8dfb868a90c8ae9b892b7abc31be5ba3c5cef42980107c8db07f5f3e63f7509c`. Arbitrage was unchanged. No installed instance or save was edited.

The earlier evidence below records previous showcase revisions.

## Hub sign wording update — 2026-11-07

Each of the ten destination stations and the monitoring station now has three adjacent signs, ordered left to right when approached from the hub center. Together with the welcome sign, the hub contains 34 signs. The normal sign limit was verified against Minecraft 1.21.1's `SignBlockEntity.getMaxTextLineWidth()` (90 pixels); every generated line fits the conservative default-font width bound. All front/back messages and inward-facing rotations were checked in the emitted NBT. The hub size and every non-sign block and block-entity payload, including the supplied book, were compared with the previous structure and remain identical. The embedded JAR asset matches the authored NBT. All 28 showcase installer contracts passed. This text/layout pass did not run a new graphical client test. Signs remain literal English, as requested.

The gameplay evidence below predates this sign-only update.

Passed 516 headless checks across fresh global and per-save configuration sources on NeoForge 21.1.243 using the matching packaged JED 2.0.0 / Arbitrage 1.0.0 pair. Both servers closed normally. Optional integrations are recorded in each trial receipt.

Checks exercise extraction from the production JAR; eleven native dimensions and their prepared safe spawns; weighted noise-terrain generation; authored floor/ceiling buildings; native item/block-entity decoding; actual shelter, exposure, protective gear and access rules; source/arena state restoration including permanent extra hearts; personal/team instance creation, terrain and scoped-state separation; current team membership; and actual optional Chunky readiness. Separate installer contracts cover preservation, partial repair, traversal, wrong path types and size/count limits.

A copied-world integrated client passed 9 checks and closed normally.

The saved Nightmare previews are historical. No installed modpack or existing player save was modified. Fluent-speaker translation review and every modpack/shader combination remain outside these checks.

- JED SHA-256: `222ccdf39d780c3524a731e358b8ccfb4ea3da7d9b26b6db2ab55fc08719e40a`
- Arbitrage SHA-256: `f57cf942f42929fc3f29538feeaa30f42633d05e9c06a1ba7e7a845a2b20e420`

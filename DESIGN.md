# La Nature for Java: design

This mod ports the Bedrock resource pack "La Nature by Carrot" (CurseForge project 1229955) to Minecraft Java
26.3 on Fabric. Most of what the pack does is a program running on Bedrock's client engine, not
just a set of textures. Its particles probe the blocks around them, its player entity scripts and
animation controllers choose ambient effects, and Molang drives everything. So the port has two halves:

1. **A build-time converter** (`src/converter`) turns the `.mcpack` into Java resources: block and item
   textures, blockstates and models, sounds, language files, plus the Bedrock JSON the runtime
   interprets, normalised to strict JSON.
2. **A client runtime** (`src/client`) implements the part of Bedrock's client engine the pack uses:
   Molang, the particle system, client entity scripts with animation controllers, pack settings, fogs, and the
   bridges from Java's own events (particle spawns, block ticks, sounds) to the Bedrock effects the pack overrides.

The repository holds no assets from the pack. The pack's licence (All Rights Reserved) forbids
redistributing it, so the build reads the user's own downloaded `.mcpack` (`-Planature.pack=...`). The jar it
produces is for personal use only.

## Inputs

| Input | Used for |
|---|---|
| The `.mcpack` (root plus the `ln` subpack, "The La Nature experience") | everything |
| Mojang `bedrock-samples` resource pack (tag matching the pack's engine) | vanilla `blocks.json`, `terrain_texture.json`, `item_texture.json`, vanilla textures and particles the pack references but does not override |
| GeyserMC `mappings` (generated for Java 26.3 / Bedrock 1.26.60, MIT) | Java block state → Bedrock block and states (`blocks.nbt`); Java particle type → Bedrock effect (`particles.json`); Java sound event → Bedrock sound (`sounds.json`) |
| GeyserMC `PackConverter` `mappings/textures.json` (MIT) | Java texture name ↔ Bedrock texture name where they differ |
| Minecraft 26.3 client jar (from Loom) | vanilla Java blockstates, models and textures to retexture |

`tools/fetch-refs.sh` clones the reference repositories at pinned commits into `refs/` (gitignored).

## Converter

* **Layered lookup.** The `ln` subpack overlays the pack root, and the pack root overlays vanilla Bedrock.
  Texture paths have no extension, so a lookup tries `.png`, then `.tga` (TGA is decoded to PNG).
* **Texture identity.** A Bedrock texture corresponds to a Java texture by PackConverter's name table, then by
  identical name (`textures/blocks/x` → `block/x`), then by identical pixels between the two vanilla packs.
* **Path overrides.** Where a pack file sits at a vanilla Bedrock texture path (items, entities, environment,
  colormaps), the converter writes it over the Java equivalent.
* **Tile overrides.** The pack's `terrain_texture.json` and `blocks.json` change which textures a block uses,
  including weighted random variations, data-indexed lists and `blockshape`. For every Java block state that
  Geyser maps to an affected Bedrock block, the converter flattens the vanilla Java model and gives each element
  face the Bedrock texture for the same face. It finds that face by texture identity, falling back to the face's
  direction. Variations become weighted blockstate variants. A `blockshape` replaces the Java geometry with the
  equivalent Java template (`fire`, `cross`, `flowering_azalea` and so on).
* **Flipbooks.** `flipbook_textures.json` becomes `.png.mcmeta` animations (`ticks_per_frame` → `frametime`,
  `blend_frames` → `interpolate`, `frames` → `frames`).
* **Runtime data.** The particles, client entities, animation controllers, animations, render controllers, geometry,
  attachables, fogs, client biomes, manifest settings and language files go to `assets/lanature/bedrock/`. Textures
  the runtime references go to `assets/lanature/textures/bedrock/<sanitised bedrock path>.png`, which a
  directory source puts in the particle atlas.
* **Triggers.** `assets/lanature/bedrock/triggers.json` maps Java particle types to the Bedrock effect identifiers
  the pack overrides. It comes from Geyser's table plus `src/converter/resources/particle_events.json` (Bedrock
  level events that engine code turns into effects).

## Runtime

* **Molang** (`molang/`): parser to an AST, then evaluated through closures. It supports the variable scopes
  `v.`, `t.`, `c.` and `q.`, structs (`v.color.r`), strings, ternaries and null-coalescing, brace blocks with
  `return`, `loop`/`for_each`/`break`/`continue`, and the `math.*` functions. Queries go through a registry
  bound to the evaluation context (an entity, an emitter or a particle).
* **Particles** (`particle/`): emitters and particles that follow the Bedrock component set the pack uses (see
  `particle/components`). They render through a `QuadParticleRenderState` subclass that is appended to vanilla's
  particle render state. Block probing (`particle_expire_if_[not_]in_blocks`) maps Bedrock block names to Java
  blocks through Geyser's table.
* **Materials** (`particle/Material`): the pack's `materials/particles.material` (exported as
  `bedrock/materials/particles.json`) over a built-in table of Bedrock's base particle materials
  (`bedrock/engine/particles.material.json`, inferred: the game does not ship it in any pack). Inheritance and the
  `+/-defines`, `+/-states`, `depthFunc`, `blendSrc`/`blendDst` keys resolve to blending, depth write and test,
  culling, `ALPHA_TEST` (cut-out at 0.5) and `ENABLE_FOG`. Each distinct state is one particle layer with its own
  pipeline and OIT set, compiled by the game on first use. Sampler states (bilinear filtering) are not reproduced.
* **Engine triggers**: Java particle spawns the pack overrides start its effect instead (`triggers.json`). Manual-rate
  effects become one-shot emitters. The torch flames are block seeds (invisible 20 s particles that must stay in the
  torch and emit the visible sparks); Java's animate tick re-triggers them about twice a second per torch, so the
  system keeps one live seed per effect per block.
* **Client entities** (`entity/`): for every entity within 64 blocks of the camera whose Java type the pack defines
  (Bedrock ids mapped where Java renamed them; `bed` and `cushion` have no Java entity), on the client tick before the
  particles: Bedrock's engine variables (`v.is_first_person`, `v.attack_time`...), `initialize` once, `pre_animation`,
  then the `animate` list. Temp variables last the whole frame, so controllers read what `pre_animation` computed.
  Controllers take the first true transition per tick, run `on_exit`/`on_entry`, start the state's particle effects
  (stopped when the state is left) and sounds, and play the state's animations; animations fire their timed
  `timeline`, `particle_effects` and `sound_effects` and loop, hold or end. Anything not played in a tick (its weight
  fell to 0) is reset and its effects stopped. Effects anchor to geometry locators (`(-x, y, -z)/16` in Java model
  axes, scaled by `scripts.scale`, turned by the body yaw), without bone animation. Sounds are the converted Bedrock
  definitions; the local player's go to the ambient category, since the pack hosts its world ambience on the player.
  Model rendering comes later.
* **Biome looks** (`environment/`): each Java biome's Bedrock biome (`biomes_java.json`) gets its fog and colours from
  the pack. The legacy `biomes_client.json` gives a biome's `fog_identifier` and `water_surface_color`, its `default`
  entry filling missing fields and standing for every unlisted biome; the pack's `client_biome` files override those
  per component and add `sky_color`, `grass_appearance` and `foliage_appearance`. Bedrock does not document which of
  the two formats wins within one pack; the newer one is taken. Vanilla Bedrock's client biomes are not read: what
  the pack leaves alone stays Java's. Fogs follow Bedrock's fog stack: the biome's fog over the data default (the
  `default` entry's fog), setting by setting, then Java's own values. Vanilla fogs the pack names are exported under
  `bedrock/vanilla/fogs/`.
  * Air and water fog distances and colours and the sky colour are an environment attribute layer of the client
    level, after Java's biome layer and before time of day and weather, so Java's night and rain darkening apply to
    them. At the camera they are averaged over nearby biomes with Java's own biome kernel. `render` distances are
    fractions of the render distance.
  * Rain is Bedrock's `weather` fog: its colour takes over from the air colour with the rain level, in place of Java's
    darkening of the fog colour in rain and thunder (Bedrock's own default weather fog, a mid grey, would be nearly
    black under it; inferred), and its distances replace Java's rain fog offsets, by Java's rain fog factor (rain where
    the sky is visible, eased).
  * Water surface, grass (base colour, under Java's swamp and dark forest modifiers) and foliage colours come from
    the biome's look. Water transparency and Bedrock's water transition fog (Java has its own underwater vision ramp)
    are not reproduced.
* **Settings**: the manifest's 38 pack settings, saved to `config/lanature.json`, behind the Molang queries
  `get_pack_setting`, `is_pack_setting_enabled` and `is_pack_setting_selected`. The pack's settings screen (key
  binding, unbound by default, and Mod Menu) shows the pack's texts with Bedrock's formatting codes.

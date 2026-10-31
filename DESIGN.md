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
| Mojang `bedrock-samples` resource pack (tag matching the pack's engine) | vanilla `blocks.json`, `terrain_texture.json`, `item_texture.json`, vanilla textures, particles, geometry, render controllers and animations the pack references but does not override |
| `bedrock-samples` behaviour pack (same commit): `biomes/`, `entities/` | biome tags; entity scales (`minecraft:scale`) |
| `bedrock-samples` v1.21.0.3, `resource_pack/models` | the bind poses the current samples lost (see Converter) |
| bridge.'s copy of the vanilla resource pack (1.16.100 beta), `materials/entity.material` | vanilla Bedrock's entity materials: the last version shipped as data, before materials were compiled into the game |
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
* **Bind poses.** The pinned samples (v1.26.60.5) re-export several legacy geometries (format 1.8 and 1.10) without their
  bones' `bind_pose_rotation`: the fox's body (90°) and tail (80°), the cat's body and tail (90° each), the parrot's
  body (28.287°) and feather, the villager's hat brim. The game's animations still assume them (the fox's setup
  animation's `"-this"` leaves its body at 90°), so drawn without them those bodies stand upright. `BindPoses` copies
  a bone's bind pose from v1.21.0.3 (which still has them) when the current bone has neither a rotation nor a bind pose and the same cubes.
* **Entity scales.** `bedrock/entity_scales.json`: each pack client entity's behaviour `minecraft:scale` from its
  components, its baby groups and its other groups (the cat's `cat_adult` 0.8, the salmon's three sizes). Java
  bakes the same sizes into its model meshes; Bedrock's client data never sees them.
* **Icon.** `assets/lanature/icon.png`, the mod's icon in `fabric.mod.json`: the pack's `pack_icon.png` scaled to
  256 pixels square (halved in bilinear steps), so the repository carries no pack image either.
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
  fell to 0) is reset and its effects stopped. `start_delay` and `loop_delay` hold an animation back (the copper
  golem's head spin waits 10 to 12 s); while waiting it poses nothing and fires nothing. Effects anchor to geometry
  locators (`(-x, y, -z)/16` in Java model axes, scaled by the entity's scale, turned by the body yaw), without bone
  animation. Sounds are the converted Bedrock definitions; the local player's go to the ambient category, since the
  pack hosts its world ambience on the player. Engine variables Bedrock's code sets for particular mobs come from the
  state Java's renderer reads: the cat's `state` (0 sneak, 1 sprint, 2 sit, 4 lying down while its lie-down amount is
  above 0, else 3 walk, in the precedence of Java's cat model), `liedownamount`, `liedownamounttail` and
  `lieonplayer`; a fish's `AnimationAmount`, taken to advance one per tick (inferred, as Java's fish tails run on age).
* **Entity models** (`model/`): an entity is modelled when the pack gives it its own geometry or render controller and
  every render controller it names exists (the player and the villager stay Java's). Its render controllers, in
  order, each give a geometry, texture, per-bone materials (glob patterns, later rules over earlier) and part
  visibility (likewise in order: `{"*": false}` then `{"light": true}` shows only the light). Each part draws over the
  ones before (`SubmitNodeCollector.order`), as Bedrock layers eyes and cracks.
  * Geometry is kept in Blockbench's axes (Bedrock's with X mirrored: a point `(x, y, z)` is `(-x, y, z)`, a rotation
    `(-x, -y, z)`, applied Z·Y·X), in both the modern and legacy formats with box and per-face UV. A cube's rotation
    and its bone's `bind_pose_rotation` are baked into the faces: the bind pose turns the bone's own cubes about its
    pivot, under its animation, and not its children (the cat's second tail segment, a child of the first, has the
    same 90° bind pose and continues it).
  * Animations pose the bones every frame, at the frame's partial tick, by the weights the scripts settled on that
    tick. Channels add rotation and position by the weight and multiply scale; `this` is the component's value so far,
    the rest rotation included and the bind pose not. A bone is its parent's transform, then a translation to its
    pivot plus position, its rotation, its scale and the translation back.
  * The model is drawn inside Java's `LivingEntityRenderer.submit`, in place of `submitModel`: Java's body yaw, death
    fall, sleeping and upside-down turns, attribute scale, hurt overlay, invisibility, shadow and name tag stay. What a
    renderer adds for its own model is skipped: its `setupRotations` override (a fish's roll on land, the cat's
    lie-down roll; only `LivingEntityRenderer`'s own runs), its `scale`, and its layers. The model's size is the client
    entity's `scripts.scale` times the behaviour scale its Java state picks (baby, salmon size), in place of Java's
    age scale.
  * A modelled entity that is not living (experience orbs, the fishing bobber, end crystals, thrown pearls and
    potions) is drawn from `EntityRenderDispatcher.submit`, at its position turned to its yaw, before its Java renderer
    runs; the renderer still draws what Bedrock's engine draws around the model (the fishing line, the crystal's beam
    to the dragon, name tag and leash), less its own model, which a mixin per renderer drops. Queries these read: an
    orb's `q.texture_frame_index` is Java's orb icon (0 to 10, by value); a thrown potion's `q.variant` is the index of
    its bottle in the potion texture array, by its first effect (the turtle master's by the potion), 30 (the plain
    bottle) for no effect (inferred: the game sends the potion and the client picks the bottle).
  * Entities the pack scripts but does not remodel (the warden) keep Java's model; their queries still matter to the
    scripts' particles and sounds. The warden's: `is_emerging`, `is_digging`, `is_roaring` and `is_sniffing` are its
    Java poses, `is_sonic_boom` the 60 ticks of Java's attack from its animation's start; `heartbeat_phase` and
    `time_since_last_vibration_detection` come from Java's 10-tick heart and tendril countdowns (inferred: phase
    `1 - heart`, time `(1 - tendrils) * 0.5 s`, so Bedrock's `0.8 * (1 - phase)` and 0.5 s tendril fade follow Java's).
  * Materials (`EntityMaterial`): vanilla's entity materials (`bedrock/vanilla/materials/entity.json`), then the
    port's additions for mobs newer than them (`bedrock/engine/entity.material.json`, inferred), then the pack's
    `materials/entity.material`. They are drawn by `lanature:core/bedrock_entity`, Java's entity shaders with Bedrock's
    alpha test, emissive rule and overlay: with `USE_EMISSIVE`, `rgb *= mix(1, light, alpha)`, so a texel's alpha is
    how much it takes the light and a transparent texel glows; with `ALPHA_TEST` such a material discards only texels
    with nothing in them (`r+g+b+a ≈ 0`), others below alpha 0.5. With `USE_OVERLAY` (vanilla's base `entity` has
    it, so nearly every material does) the texel is mixed toward the render controller's `overlay_color` by its alpha,
    before the light; the colour rides in the vertices' overlay coordinates, and Java's hurt and white flashes are
    turned into the same form, as Bedrock's hurt colour takes over the overlay.
  * Colour masks: with `USE_COLOR_MASK`, as in Bedrock's 1.16 entity shader, a texel is tinted toward
    `rgb * change` by its own alpha and its alpha scaled by the change colour's, and `ALPHA_TEST` discards only zero
    alpha. The change colour is the render controller's `color` (`this` in it being the engine's colour), else the
    engine's colour: the behaviour pack's `minecraft:color`, a tame cat's or wolf's collar or a sheep's wool, here
    Java's same dye; white for the rest. The pack's tame cat textures depend on it: the body at alpha 4 (drawn,
    barely tinted), the collar's texels at 255 (dyed). It reaches the shader as the vertex colour (RGB passed on
    unlit). The materials' `DisableAlphaWrite` is kept.
  * `uv_anim` (`offset`, `scale`) moves the texture coordinates of `USE_UV_ANIM` materials, `uv = offset + uv * scale`
    as Bedrock's vertex shader does; the port applies it to the vertices (the pack's vanilla-style experience orbs pick
    their frame of one atlas this way).
  * Not yet: the glowing outline, `surface_particle_*` (block-textured particles: the warden's dig and emerge
    effects), and held and worn items.
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

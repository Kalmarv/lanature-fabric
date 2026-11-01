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
| `bedrock-samples` vanilla data (same commit): `metadata/vanilladata_modules/mojang-blocks.json` | the current block names, to tell a removed name in a particle block test from a block Java lacks |
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
  colormaps), the converter writes it over the Java equivalent. The food bar's flash and blink pictures
  (`hunger_flash_*`, `hunger_effect_flash_*`, `hunger_blink`) are states Java's food bar does not have (it has six
  sprites and no flash), so they go unused.
* **Tile overrides.** The pack's `terrain_texture.json` and `blocks.json` change which textures a block uses,
  including weighted random variations, data-indexed lists and `blockshape`. For every Java block state that
  Geyser maps to an affected Bedrock block, the converter flattens the vanilla Java model and gives each element
  face the Bedrock texture for the same face. It finds that face by texture identity, falling back to the face's
  direction. Variations become weighted blockstate variants. A `blockshape` replaces the Java geometry with the
  equivalent Java template (`fire`, `cross`, `flowering_azalea` and so on).
* **Block items.** Bedrock draws a block's item with the entry's `carried_textures` where it has them (151 vanilla
  entries; coloured-in pictures for the inventory, the leaves and lily pad green where their block textures are grey),
  else with its block textures. A Java block item whose Bedrock pictures come from the pack gets the same face
  matching against its item model (a block model, or `item/generated` layers), at the first variation of each tile
  (which variation Bedrock's item shows is not known); an item drawn with a carried picture loses Java's tint. Items the
  pack leaves alone keep Java's icon.
* **Potions.** Bedrock draws a potion with a picture per effect (`textures/items/potion_bottle_[splash_|lingering_]<effect>`).
  `src/converter/resources/potion_textures.json` names each Java potion's Bedrock effect; each item's definition
  selects on its `potion_contents` and draws the potions of an effect the pack redraws with that picture, the rest
  with Java's tinted bottle.
* **Colour maps.** Bedrock's `birch`, `evergreen`, `swamp_foliage` and `mangrove_swamp_foliage` maps have no Java
  picture (Java uses constants and biome colours of the same values). Those the pack replaces go to
  `bedrock/colormaps/` with `bedrock/colormaps.json`, which also lists the vanilla client biomes that name a map; the
  maps replacing Java's own `grass` and `foliage` are path overrides.
* **End portal colours.** Bedrock draws the portal as 16 star planes added together, plane `L` taking the colour
  table's texel at the centre of 4×4 cell `L - 1` (point-sampled, so an 8×8 table is read at its odd texels) times
  `L / 16` (the 1.0 client's layout and the published pre-RenderDragon shader; the current renderer's is unknown).
  Java's end portal shader also adds its layers, each times a constant colour that brightens with depth, so the
  converter writes Java's `rendertype_end_portal.fsh` with its `COLORS` table from the pack's: layer `i` = plane `i + 1`.
  Java's star textures and parallax stay.
* **Weather.** Bedrock draws rain and snow as particles showing cells of the `weather` sheet (snow: eight cells along
  the top eighth; rain: eight cells an eighth wide and half the sheet tall below them, as the community client
  cinnabar reads it), each stretched over half a block (rain) or a fifth of one (snow). Java scrolls whole sheets.
  `rain.png` and `snow.png` are Java's own, redrawn at 8× with the pack's sprite at each Java streak or flake, at
  Bedrock's lengths (Java's sheet is 64 pixels to the block).
* **Flipbooks.** `flipbook_textures.json` becomes `.png.mcmeta` animations (`ticks_per_frame` → `frametime`,
  `blend_frames` → `interpolate`, `frames` → `frames`).
* **Runtime data.** The particles, client entities, animation controllers, animations, render controllers, geometry,
  attachables, fogs, client biomes, manifest settings and language files go to `assets/lanature/bedrock/`. Textures
  the runtime references go to `assets/lanature/textures/bedrock/<sanitised bedrock path>.png`, which a
  directory source puts in the particle atlas; `atlas.terrain` and `atlas.items` stay named (the runtime reads Java's
  block and item atlases). Vanilla effects the pack starts without defining go to `bedrock/vanilla/particles/`:
  those its particle events spawn, those its client entities and attachables (and the vanilla definitions exported
  for them) name in `particle_effects` (the warden's `minecraft:warden_dig`), and those these spawn in turn; one
  neither defines is reported and spawns nothing, as in Bedrock. Item tags and cooldown types go to
  `bedrock/items.json` (see the player's item queries). Render controllers the engine defines without a file (`controller.render.iron_golem`,
  which vanilla's iron golem names) come from `bedrock/engine/render_controllers.json`, as the default controller
  (inferred).
* **Sounds.** Pack sound definitions become `lanature:<event>` (and replace the Java events Geyser plays as them);
  pack files are copied. A vanilla file a definition names resolves through PackConverter's path table, then the same
  path, then the file in the same place among the distinct files of the Java event Geyser plays as the definition
  (Java keeps the loom's and stonecutter's sounds under `ui/`, the enchanting table's under `enchantment_table/`, the
  goat's long jump that Bedrock calls `component.jump_to_block` under `mob/goat/`).
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
  level events that engine code turns into effects). `trigger_sites.json` names the game events at which Bedrock's
  engine starts an effect itself (Java spawns a burst there, or Bedrock gives one Java type's uses to different
  effects): each site's effect, kept under `sites` when the pack defines it, and the Java types it takes over, which
  never map particle by particle. Types in `trigger_skip.json` (the effect reads state the Java particle lacks) stay
  vanilla.

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
  culling, `ALPHA_TEST` and `ENABLE_FOG`. As in Bedrock's particle shader (reconstructed from the 1.20.20 renderer;
  sourced), `ALPHA_TEST` cuts out texels under half the *texture's* alpha, before the tint, and draws the rest at
  alpha 1, so the tint's alpha does nothing there: the pack's `particles_glow` campfire glows (texture alpha at most
  0.17) draw nothing, and its alpha-tested snowflakes do not fade. Each distinct state is one particle layer with its
  own pipeline and OIT set, compiled by the game on first use. The main sampler's states (`samplerStates`,
  `+samplerStates` merged by `samplerIndex`) are reproduced for linear filtering (`Bilinear`, `Trilinear`) and
  `Clamp` wrapping: such an effect draws its texture alone (`SampledTexture`, registered with the texture manager
  under `lanature:sampled/<filter>_<wrap>/…`) rather than from the particle atlas, whose packed sprites would bleed
  into each other when filtered.
  An effect on Bedrock's block or item atlas (`atlas.terrain`, `atlas.items`) draws from Java's block or item atlas,
  read whole at the UVs it is handed, so each material has one layer per atlas.
* **Engine sites** (`particle/SiteEffects`, `particle/PictureEffects`): at these game events the pack's effect starts
  in place of Java's particles (`sites` in `triggers.json`, by site name):
  * `minecraft:block_destruct` for a broken block, a crack while mining, a sprint step, a fall's burst from the server
    and a brushed flake (Bedrock's uses of it; Java's other `block` particles, the warden's digging and the breeze's
    jumps, are other effects in Bedrock, which the pack leaves alone, so they stay Java's). The engine's amounts are
    not published; the pack's own effect counts 48 particles as a broken block and one slow particle
    (`v.velocity_scalar` under 0.6) as a step, so (inferred): a break is 48 particles at speed 1 over the block's
    shape, a crack one at Java's crack power 0.2, a step one at the entity's speed in blocks per tick, a fall the
    server's count at its speed, a flake one at speed 1.
  * `critical_hit_emitter` and `totem_particle` for Java's crit and totem tracking emitters, at the entity's eyes
    and following it as Java's emitter does (the vanilla effects emit 0.5 to 0.9 blocks below their origin, and the
    pack's totem draws its circle 1.6 below, at a player's feet; inferred). The pack's totem keeps its fire 40 s and
    its stars 45 s (Fire Resistance's and Regeneration's times) in its default `high_detail_mode`.
  * `splash_spell_emitter` for a splash potion, plain or instant (Geyser sends both as Bedrock's one splash event),
    with the potion colour as `v.color`, in place of Java's 100 spell particles; the bottle's shards stay item
    particles.
  * `smash_ground_particle` and `smash_ground_particle_center` for a mace smash, at the top of the ground block.
  * Java's `entity_effect` is several Bedrock effects, each started with one particle where Java spawns its one, in
    its colour: an entity's status-effect swirl `mobspell_emitter` (`mobspell_ambient` for an ambient effect, whose
    Java particle has alpha 38/255), a tipped arrow's trail `arrow_spell_emitter`, as is the spectral arrow's (a Java
    `effect` particle). A lingering cloud is `mobspell_lingering`, which reads `v.cloud_radius`,
    `v.particle_multiplier` and `v.cloud_lifetime` and (in vanilla) emits `π r²` times the multiplier once: Java's
    count over the cloud each tick, so (inferred) it starts each tick Java spawns them, with a multiplier of 1,
    Java's radius (0.2 while the cloud waits) and an active time of one tick. Java's other `entity_effect` and
    `effect` particles (an evoker's spell, a stunned ravager, a raid bell, the wither, a brown mooshroom) stay Java's.

  Effects that draw a block's or an item's picture get its UVs in Java's atlas (`v.emitter_texture_coordinate`/`size`,
  `v.surface_particle_texture_*`, `v.EmitterTextureCoord`/`Size`) and Java's terrain particle colour (0.6 grey times
  the block tint). Each Java `item` particle starts `breaking_item_icon`, or `breaking_item_terrain` when the item's
  picture is in the block atlas (a block-drawn item), with one particle.
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
  golem's head spin waits 10 to 12 s); while waiting it poses nothing and fires nothing. An animation whose time
  follows a query (`anim_time_update`: vanilla's walk cycles run on the distance walked) starts where the query is,
  wrapped into a loop (at most the end of one that plays once), then moves as far as the query moves; a query that
  goes back moves the time back without firing what it passes. Effects anchor to geometry
  locators, which move with their bones: each tick an emitter reads its locator through the bone's matrix as last posed
  for drawing (the model's, or the Bedrock skeleton a Java model takes its pose from), turned from the model's frame to
  Java model axes, scaled by the entity's scale and turned by the body yaw; before the entity is first drawn, and for
  an attachable, the geometry's rest point (`(-x, y, -z)/16`). An emitter bound to a locator emitter
  (`emitter_bound`) keeps its offset from that locator. Sounds are the converted Bedrock definitions; the local player's go to the ambient category, since the
  pack hosts its world ambience on the player. Engine variables Bedrock's code sets for particular mobs come from the
  state Java's renderer reads: the cat's `state` (0 sneak, 1 sprint, 2 sit, 4 lying down while its lie-down amount is
  above 0, else 3 walk, in the precedence of Java's cat model), `liedownamount`, `liedownamounttail` and
  `lieonplayer`; a fish's `AnimationAmount`, taken to advance one per tick (inferred, as Java's fish tails run on age).
* **Entity models** (`model/`): an entity is modelled when the pack gives it its own geometry or render controller and
  every render controller it names exists (the villager stays Java's; the player keeps Java's model, posed by the pack's
  animations, below). Its render controllers, in
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
    bottle) for no effect (inferred: the game sends the potion and the client picks the bottle). An end crystal's
    `q.show_bottom` (vanilla's render controller hides the base without it) is Java's `ShowBottom`: the exit pillars'
    crystals have a base, those players place have none. The bobber's `q.relative_block_has_any_tag` reads Bedrock
    block tags, which no published data lists; the one the pack asks for, `water` (Bedrock's water and flowing water,
    sourced), is Java's water block, and any other is logged and matches nothing. Bedrock's hook floats still on the
    surface, out of the water, where Java's bobs just under it, touching the water: a Java hook bobbing, not bitten
    and with its eye above the water reads as not in water and at no speed (`is_in_water`, `ground_speed`,
    `vertical_speed`), so the pack's float states play; its dive after the cast and a bite pulling it under still
    count as in the water (inferred).
  * Entities the pack scripts but does not remodel (the warden) keep Java's model; their queries still matter to the
    scripts' particles and sounds. The warden's: `is_emerging`, `is_digging`, `is_roaring` and `is_sniffing` are its
    Java poses, `is_sonic_boom` the 60 ticks of Java's attack from its animation's start; `heartbeat_phase` and
    `time_since_last_vibration_detection` come from Java's 10-tick heart and tendril countdowns (inferred: phase
    `1 - heart`, time `(1 - tendrils) * 0.5 s`, so Bedrock's `0.8 * (1 - phase)` and 0.5 s tendril fade follow Java's).
    Its dig effect (vanilla's `warden_dig`, started from the pack's digging animations) samples the block it stands on
    through `surface_particle_texture_coordinate`, `_size` and `_color`: that block's picture in Java's block atlas
    (Java's `getOnPos`, the block Java's warden digs its particles from) and Java's terrain-particle colour for it.
    Vanilla Bedrock's warden digs through the same effect, so Java's own dig particles (block particles its client
    spawns) give way to it while the pack's scripts run for the warden.
  * Materials (`EntityMaterial`): vanilla's entity materials (`bedrock/vanilla/materials/entity.json`), then the
    port's additions for mobs newer than them (`bedrock/engine/entity.material.json`, inferred), then the pack's
    `materials/entity.material`. They are drawn by `lanature:core/bedrock_entity`, Java's entity shaders with Bedrock's
    alpha test, emissive rule and overlay: with `USE_EMISSIVE`, `rgb *= mix(1, light, alpha)`, so a texel's alpha is
    how much it takes the light and a transparent texel glows; with `ALPHA_TEST` such a material discards only texels
    with nothing in them (`r+g+b+a ≈ 0`), others below alpha 0.5. The main texture's sampler states (linear
    filtering, clamping: the pack's xp orb, allay, cross and mace glows) go with the texture binding. With `USE_OVERLAY` (vanilla's base `entity` has
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
  * `filter_lighting` (the pack's splash and lingering potion controllers; vanilla's sprite entities: arrows, item
    sprites, bottles, rockets) is read by no code: what it changes in Bedrock's lighting is not published, so the
    potions are lit as other entities are.
  * Glint: a material with `GLINT` (vanilla's `entity_alphatest_glint` and its kin) adds Java's enchantment glint as
    vanilla's `core/entity` does, Java's glint texture and texturing (`ENTITY_GLINT_TEXTURING`) standing in for the
    `enchanted` texture (`textures/misc/enchanted_item_glint`) and its scrolling, which Bedrock's compiled shaders do
    without published data (inferred). The pack's spears and mace choose it by `v.is_enchanted`, which an attachable
    instance sets from its item's foil (Java's `hasFoil`: enchanted, or glint by component); its splash and lingering
    potions always draw it (`splash_potion_enchanted`, `lingering_potion_enchanted`), as vanilla Bedrock's do.
  * The glowing outline: as Java's `submitModel` does, a glowing entity's parts are submitted again in their
    materials' outline render types, full bright, in the outline colour.
  * Worn items: none. Every attachable of the pack is a held item, chosen by `c.item_slot` (`main_hand`, `off_hand`),
    and none is of an item worn in an armour slot; the pack has no armour textures either, so worn armour stays Java's.
* **Held items and the player's pose** (`entity/`, `model/AttachableRenderer`, `model/HumanoidPose`): the pack's
  `attachables/` (root `minecraft:attachable`) load like client entities, keyed by item. For each living entity that
  is drawn, each hand holding an attachable item runs an instance of it: its queries act on the holder, its context is
  `c.item_slot` (`main_hand` or `off_hand`, by the arm and the holder's main arm) and `c.is_first_person`, and
  `c.owning_entity` is the holder, whose variables `->` reads: `c.owning_entity->v.attack_time` evaluates on the
  holder's instance and sees only the variables its client entity declares public (`scripts.variables`), as Bedrock's
  vanilla spear reads the player's. The item model is drawn in place of Java's item in the hand
  (`ItemInHandLayer.submitArmWithItem`), in the arm's frame: the root bone binds to `rightitem`/`leftitem` and Java's
  arm frame becomes Bedrock's with `scale(-1, -1, 1)` and `translate(±1/16, -31/16, 1/16)`.
  * The pack replaces the whole player animation set, and its item poses depend on its own arm animations (the
    lantern hangs from an arm raised by `holding_hanging_item`), so the player's Java model (skin, armour, cape and
    layers kept) takes the pose of the pack's player geometry: each Java part gets its Bedrock bone's whole transform
    made relative to its Java parent (`HumanoidPose`, applied after `HumanoidModel.setupAnim`), and parts the render
    controllers hide are hidden. The slim skin uses `geometry.humanoid.customSlim`. The inventory's paperdoll keeps
    Java's pose and items: the scripts run once per entity, for the camera's view, and in first person that view's
    pose is the first-person one.
  * First person (`FirstPersonHandsAndItemsRenderer.submitArmWithItem`, for an attachable or an empty hand): the
    player model posed by the pack's first-person animations, only the arm drawn, its attachable in the hand. The base
    pose turns the body with the view about its neck pivot, and the arm poses put the arms behind the body on its far
    side; drawn with the neck pivot at the eye in Java's own entity frame and turned a half turn about the view's
    vertical, the arms stand still in view at any pitch and head turn and come before the eye on their own side
    (inferred; the vanilla empty hand lands within a few pixels of Java's). One about the world's vertical instead
    turns the pitch the wrong way. `v.player_arm_height` and `c.player_offhand_arm_height` are Java's hand heights
    (the swap animations lower the arms by `10 * (1 - height)` pixels).
  * Engine variables the player's animations need beyond the entity ones: `gliding_speed_value` (Java's
    `speedValue`: walk cycles divide by it, so unset it makes the limbs NaN), `is_holding_right`/`left`,
    `is_brandishing_spear`, `is_holding_spyglass`, `is_tooting_goat_horn`, `is_using_brush` (by the use animation),
    `damage_nearby_mobs` (riptide), `is_sneaking`, `swim_amount`, `player_x_rotation`; VR and split screen are 0.
    `q.ticks_since_last_kinetic_weapon_hit` is -1 until the first spear hit (Java's 0 for never would loop the spear's
    hit animation, which plays from 0 to 5). `item_use_duration` is how long the held item has been used, in ticks
    (the vanilla mobs' spear scripts time the charge by it). For the player holding a spear, the third-person spear
    variables (`tp_melee_spear_base_arm_rotation_x` and the `tp_melee_spear_use_*` arm, item and attachable angles)
    are what the vanilla zombie, husk, zombie villager, piglin and zombified piglin compute in their own
    `pre_animation` from the spear's kinetic timings (`KineticWeapon`'s delay and its conditions' longest durations);
    inferred, the player's being engine code; the pack's spear attachable turns its pole by
    `tp_melee_spear_use_attachable_rotation_z`.
  * Eating and drinking (Java's `EAT` and `DRINK` uses): `use_item_startup_progress` runs 0 to 1 over the wait
    before a consumable's first bite (Java's `consumeTicks * 0.21875`, 7 of food's 32 ticks) and holds, and
    `use_item_interval_progress` runs 0 to 1 over each 4 ticks after it (the bites' interval); both 0 otherwise.
    Inferred: the names fit Java's phases and no published source defines them. They play vanilla's humanoid
    `use_item_progress`, which raises the right arm to the mouth (the pack's controller leaves it out for the
    two-handed salmon, the bow, the trident, spyglass, goat horn and brush), where Java's third-person arm stays down.
  * Item queries: the pack's own spear animations (it never plays vanilla's `melee_spear` controller) key on
    `q.equipped_item_all_tags(slot, 'minecraft:is_spear')`. Bedrock item tags and cooldown types come from Geyser's
    Bedrock item components (`bedrock/items.json`, by Geyser's Java → Bedrock item table), which record them only
    for items Bedrock defines by components: the seven spears (`is_spear`, an `attack` cooldown), the wind charge (a
    `use` cooldown) and two more. The charge controllers time their states by `q.kinetic_weapon_delay` and the
    dismount, knockback and damage durations, the main hand's `KineticWeapon` delay and its conditions' longest
    durations in ticks (Java's spears and Geyser's Bedrock spears give equal values), and start only with
    `q.cooldown_time_remaining` at 0: for an item with Bedrock's attack cooldown, the time left of Java's attack
    recharge (each spear's lasts as long as its Bedrock cooldown), for any other the held item's Java item cooldown,
    in seconds. `q.item_is_charged` is whether the main hand's crossbow is loaded; `q.has_cape`, whether the skin has
    a cape and the settings show it. An empty slot's item name is `''` (`q.is_item_name_any(slot, '')` is how the pack
    tells an empty hand, which swings fully while walking where a full one swings half, 18° forward).
  * `bob_animation` is 0: it plays vanilla Bedrock's first-person walk bob and the held item's breathing, which the
    pack keeps unchanged from vanilla, and Java's own view bob already moves the hands (`GameRenderer.renderItemInHand`
    applies `bobView`), so Java's stays. `short_arm_offset_left`/`right` are 0, a Java skin's arms being full length
    (inferred: the offsets read as a shorter arm's).
  * Whole-body poses: vanilla Bedrock's swim, crawl and sleep animations (which the pack keeps) turn and move the
    geometry's `root`, placing the body for Bedrock's engine (asleep, 24 px off toward the bed's head), where Java's
    renderer turns and moves the whole model itself (`AvatarRenderer.setupRotations` for swimming, crawling and
    gliding, `LivingEntityRenderer` for sleeping and the riptide spin). While Java does (asleep, `getSwimAmount > 0`,
    gliding, spinning) the pose is taken with the root's animation out (`root_rest · root⁻¹ · bone`), so the body is
    turned once, Java's way, and the limbs keep the pack's strokes; otherwise the root is kept (sneaking leans the
    body by it, as Java leans its model in `setupAnim`). `q.item_remaining_use_duration` (the item in use in the
    named hand, main by default: seconds left, or with a normalization that times the fraction left) drives vanilla's
    crossbow controller into its `charge` state; `q.sleep_rotation` is the bed's facing as Java's yaw (inferred; only
    the root reads it). Checked in the `poses` gametest against Java's pose: standing, sneaking, crawling, swimming,
    riding a boat, gliding, sleeping, a charged crossbow, charging it, the spyglass, bow, shield and trident.
  * Not yet: the first-person and attack spear variables (no published computation).
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
    the biome's look. A colour given as a map (`{"color_map": "swamp_foliage"}`) is the map sampled by the biome's
    temperature and downfall, as Java samples its own; a biome the pack has no client biome file for keeps vanilla's
    map references (swamps and mangrove swamps), which apply where the pack replaces the map.
  * Birch and spruce leaves take their tint from the `birch` and `evergreen` maps where the pack replaces them
    (`bedrock/engine/colormaps.json`: the engine applies these maps; no data file names them), blended over nearby
    biomes like Java's foliage; otherwise, and for blocks drawn out of the world, the tint the game had for them.
  * Water transparency (`WaterOpacity`, a `FluidRenderer` mixin): the pack's water surface opacity (its legacy
    `water_surface_transparency`, an opacity despite the name, or a client biome's `surface_opacity`) over vanilla's
    for the same biome, as vanilla's stands for Java's water, scales the alpha of Java's water tint, averaged over
    nearby biomes like the water colour; Java's terrain shader multiplies its water texture's alpha (180) by it.
    Vanilla's is what the game reads now: since 1.21.40 not its own `biomes_client.json`, only its client biomes
    (`surface_opacity` 0.65 in swamps, 0.55 in warm oceans, `bedrock/vanilla/biomes/`), the rest taken to be the old
    file's default 0.65 (`bedrock/vanilla/biomes_client.json`; inferred). The pack's own legacy file is still read.
    Its default 0.35 leaves 54% of Java's opacity in most biomes, its taigas' 0.6 92%, its warm oceans' 0.35 64%.
    Its swamps' 1.0 is above vanilla's 0.65; a share above 1 cannot raise Java's texture alpha through the tint, so
    swamps draw at Java's opacity (not reproduced). The ratio reading is inferred.
  * Water fog coming in (`WaterFogEnvironmentMixin`): the water fog's `transition_fog` moves its distances from its
    `init_fog` to its own by a progress rising to `mid_percent` at `mid_seconds`, then to 1 at `max_seconds`, never
    under `min_percent`, over Java's time under water (`waterVisionTime`). Vanilla Bedrock's water fogs all use
    0.25, 5 s at 0.6 and 30 s from an end of 0.01: Java's own ramp, from which the reading is taken (inferred). The
    pack's default water fog reaches its end in 20 s. Biomes without a pack transition keep Java's ramp, weighted with
    the rest. The `init_fog` colours equal the pack's water fog colours, so the colour does not move.
* **Settings**: the manifest's 38 pack settings, saved to `config/lanature.json`, behind the Molang queries
  `get_pack_setting`, `is_pack_setting_enabled` and `is_pack_setting_selected`. The pack's settings screen (key
  binding, unbound by default, and Mod Menu) shows the pack's texts with Bedrock's formatting codes.

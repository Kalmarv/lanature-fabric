# La Nature for Java (Fabric)

A Fabric client mod that runs the Bedrock resource pack [La Nature by Carrot](https://www.curseforge.com/minecraft-bedrock/texture-packs/la-nature-by-carrot) on Minecraft Java 26.3. The build converts your own downloaded copy of the pack, and the mod reimplements the parts of Bedrock's client engine the pack depends on.

## Features

- Converts the pack's `.mcpack` into Java resources at build time, using its `ln` subpack ("The La Nature experience")
- Retextures and reshapes blocks through generated blockstates and models, including weighted random variations
- Gives block items the pack's inventory pictures and potions the pack's per-effect bottles
- Converts the pack's sounds, language files, colour maps, end portal colours, rain and snow sprites and flipbook animations
- Cuts Java's bed faces from the pack's bed sheets, and gives leashes the pack's rope colours
- Interprets Molang, with variable scopes, structs, loops, `math.*` functions and the queries the pack uses
- Runs the pack's Bedrock particle effects, with their emitters, curves, events, collisions, block tests and materials
- Starts the pack's effects in place of Java's particles for block breaking, crits, totems, potion splashes, mace smashes and status effects
- Runs client entity scripts, animation controllers and animations, with particle effects attached to model locators
- Draws the mobs and objects the pack remodels with their Bedrock geometry, render controllers and entity materials
- Draws held-item attachables in first and third person and poses the Java player model from the pack's player animations
- Applies the pack's per-biome fog, sky, water, grass and foliage colours, water transparency and underwater fog timing
- Shows the pack's settings in an in-game screen and saves them to `config/lanature.json`
- Contains none of the pack's assets; every pack file comes from the user's own download

## Getting Started

### Requirements

- Minecraft Java 26.3 with Fabric Loader 0.20.1 or later and Fabric API 0.165.0+26.3
- Java 25 (the Gradle build downloads a Java 25 toolchain if none is installed)
- Git, for fetching the reference data
- Your own copy of the La Nature `.mcpack`
- [Mod Menu](https://modrinth.com/mod/modmenu) (optional), for a settings button in the mod list

### Installation

Download the latest jar from [Releases](https://github.com/Kalmarv/lanature-fabric/releases), or build it from source:

```sh
git clone https://github.com/Kalmarv/lanature-fabric.git
cd lanature-fabric
tools/fetch-refs.sh                       # Mojang bedrock-samples, GeyserMC mappings and PackConverter, bridge. vanilla materials, into refs/
mkdir -p pack
cp ~/Downloads/La-Nature-*.mcpack pack/
./gradlew build                           # build/libs/lanature-<version>.jar
```

The build uses the last `.mcpack` or `.zip` in `pack/` by name. To use a pack elsewhere, pass `-Planature.pack=PATH` or set `LANATURE_PACK`. The converter step (`./gradlew convertPack`, run by `build`) also runs Minecraft's data generator and writes a log of what it converted to `build/generated/lanature/lanature-conversion-report.txt`.

Copy `build/libs/lanature-<version>.jar` and Fabric API into your `mods/` folder.

### Usage

The pack's effects, models, sounds and biome looks are active as soon as the mod loads. To change the pack's settings, open the La Nature entry in Mod Menu, or bind the "Open La Nature settings" key under Controls (it is unbound by default). Changes apply immediately and are saved when the screen closes.

Run the Molang unit tests with `./gradlew test`; once the pack has been converted, this also checks that every Molang expression in it parses. The client game test builds a scene in a test world, logs particle and entity state and takes screenshots:

```sh
xvfb-run -a ./gradlew runClientGameTest
LANATURE_GAMETEST=entities xvfb-run -a ./gradlew runClientGameTest
```

`LANATURE_GAMETEST` selects one scene instead of the default: `entities`, `attachables`, `items`, `colormaps`, `environment`, `pictures`, `events`, `poses` or `modmenu`.

[DESIGN.md](DESIGN.md) describes how the converter and runtime work, including which Bedrock engine values are inferred.

## Credits

La Nature is made by Carrot; all credit for the pack goes to them.

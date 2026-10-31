# La Nature for Java (Fabric)

A Fabric client mod for Minecraft Java 26.3 that runs the Bedrock resource pack
[La Nature by Carrot](https://www.curseforge.com/minecraft-bedrock/texture-packs/la-nature-by-carrot) on Java: its
textures, block models and sounds, plus a reimplementation of the parts of Bedrock's client engine the pack is
built on (Molang, the particle system, client entity scripts and animation controllers, pack settings).
[DESIGN.md](DESIGN.md) describes how it works.

## Licence

The pack is All Rights Reserved by its author. This repository contains none of its assets: the build reads your
own downloaded `.mcpack` and converts it. A jar built from it contains the pack's assets and is for personal use
only. Do not redistribute it.

## Requirements

Minecraft Java 26.3, Fabric Loader 0.20.1 or later, Fabric API 0.165.0+26.3, Java 25.

## Building

```sh
tools/fetch-refs.sh                 # reference data the converter reads (Mojang bedrock-samples, GeyserMC mappings)
cp ~/Downloads/La-Nature-*.mcpack pack/
./gradlew build                     # build/libs/lanature-<version>.jar
xvfb-run -a ./gradlew runClientGameTest   # optional: builds a test scene and takes screenshots
```

Pack settings are stored in `config/lanature.json`. Change them in game from Mod Menu or with the "Open La Nature settings"
key (unbound by default; set it under Controls).

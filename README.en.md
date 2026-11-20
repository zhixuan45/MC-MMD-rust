# MC-MMD-rust

[简体中文](README.md) · English

MC-MMD-rust brings MikuMikuDance models, animations, and real-time physics to Minecraft.

## Versions and downloads

| Minecraft | Mod loader | Java |
| --- | --- | --- |
| 1.21.1 | Fabric / NeoForge | 21 |
| 1.20.1 | Forge | 17 |

Download published builds from [GitHub Releases](https://github.com/zhixuan45/MC-MMD-rust/releases). See the [version guide](doc/VERSIONS.md#english) for the two maintenance branches, their Actions builds, and how to pick the right JAR.

## Getting started

1. Put the matching mod JAR in your Minecraft instance's `mods/` folder and start the game once.
2. Put each player model in its own folder under `3d-skin/EntityPlayer/`, for example `3d-skin/EntityPlayer/MyModel/model.pmx`. Keep the model's textures with it.
3. Hold `Alt` in game, open the model selector from the wheel, and choose your model. You can change the key binding in Minecraft's Controls settings.
4. Put VMD motions in `3d-skin/CustomAnim/` and VPD expressions in `3d-skin/CustomMorph/` to use them from the action and expression wheels.

The mod supports PMX and PMD models, layered animation, GPU skinning, and Bullet physics. See the [shader pack compatibility notes](doc/render/SHADERPACK_COMPATIBILITY.md) for the Iris pack versions and combinations covered by testing.

## Feature requests

Read the [feature request guide](doc/FEATURE_REQUESTS.md#english) and [submit a feature request](https://github.com/zhixuan45/MC-MMD-rust/issues/new?template=feature-request.yml) for new features or mod integrations.

If you would like to sponsor a feature, indicate it in the form so you can discuss prioritizing or speeding up the work with a maintainer.

## License and credits

This mod is available free of charge. Changes covered by this fork's license are for noncommercial use and prohibit R18G use. See the [license](LICENSE) for the full terms, [NOTICE](NOTICE) and the licenses bundled with each dependency for third-party licensing, and [acknowledgments](ACKNOWLEDGMENTS.md) for credits.

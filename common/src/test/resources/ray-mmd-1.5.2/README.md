Ray-MMD parser regression inputs from tag `1.5.2`, commit
`a425ab6d4219a047f8d64ac7fdc4f73c76c31dc8`:
https://github.com/ray-cast/ray-mmd/tree/1.5.2/Materials

The three Cloth parameter files and material_common_2.0.fxsub are unmodified.
The common source's SHA-256 after CR removal is
`a0383fe1fc8079ed41f5865a61f6d5a69bdcf74e74d85b8dcf88905fe4fc6304`.
MIT license: see LICENSE-Ray-MMD at the repository root.

Textures are not distributed. Parser tests create a placeholder for the normal
map; real texture decoding and rendering require the upstream resource pack.

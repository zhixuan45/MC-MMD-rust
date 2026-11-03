Ray-MMD parser regression inputs from tag `1.5.2`, commit
`a425ab6d4219a047f8d64ac7fdc4f73c76c31dc8`:
https://github.com/ray-cast/ray-mmd/tree/1.5.2/Materials

The Cloth, Skin, Hair/anisotropy, Transparent/glass, ClearCoat and Subsurface
parameter values/code and material_common_2.0.fxsub are unmodified. The added
non-Cloth parameter fixtures normalize line endings and trailing whitespace.
The common source's SHA-256 after CR removal is
`a0383fe1fc8079ed41f5865a61f6d5a69bdcf74e74d85b8dcf88905fe4fc6304`.
MIT license: see LICENSE-Ray-MMD at the repository root.

Textures are not distributed. Parser tests create placeholders for active maps;
real texture decoding and rendering require the upstream resource pack.

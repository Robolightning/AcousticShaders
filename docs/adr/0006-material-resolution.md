# ADR-0006: Portable acoustic material resolution

Status: Accepted

Shader packs target stable semantic tags (`acoustic:stone`, `acoustic:metal`, etc.) and optional explicit registry rules. Platform adapters translate version-specific metadata into `MaterialDescriptor` values. On 1.12.2 this may include registry ids, Minecraft/Forge material facts and OreDictionary names; modern adapters may use block/item tags.

Rule priority permits exact compatibility overrides without forcing packs to enumerate every modded block. OreDictionary is a compatibility fallback, never the acoustic ABI itself.

# Acoustic material database

Acoustic Shaders deliberately separates **acoustic algorithms** from **material data**.

- An **Acoustic Shader Pack** defines the processing DAG, presets, ray/wave algorithms and quality options.
- An **Acoustic Material Resource Pack** defines measured/curated acoustic materials and mapping rules from Minecraft blocks to those materials.
- The Minecraft 1.12.2 runtime always maintains a generated default resource pack named `Acoustic Shaders Default Materials` as the lowest-priority material layer.

This separation lets a visual/resource-pack author ship material corrections without forking an Acoustic Shader Pack, and lets one acoustic shader work across large modpacks.

## Generated default pack

On Minecraft 1.12.2 the runtime creates:

```text
.minecraft/resourcepacks/Acoustic Shaders Default Materials/
  pack.mcmeta
  README-AcousticShaders.txt
  .acousticshaders-generated.properties
  assets/acousticshaders/acoustic_materials/generated.json
  assets/acousticshaders/acoustic_sources/generated.json
```

The generated pack also contains the default `acoustic_sources/generated.json` source-category database described in `source-profiles.md`. It is **always consumed by Acoustic Shaders** as the lowest-priority database. RC16 also selects it in Minecraft's vanilla Resource Packs list, gives it the display name **Acoustic Shaders Default Materials** and ships a dedicated `pack.png`, so the visible vanilla UI matches the runtime state. If the player removes the vanilla selection, Acoustic Shaders will restore it on a later initialization; this visual selection is not the safety mechanism—the acoustic runtime still treats the generated database as mandatory base data. If the generated directory is deleted, its skeleton is recreated on the next launch.

Minecraft 1.12.2 keeps resource-pack selection in more than one place. RC16 migrates the historical `AcousticShaders-Generated-Materials` name in **`options.txt`, `GameSettings.resourcePacks`, and the live `ResourcePackRepository` Entry list**, then refreshes the live ResourceManager if that repository changed. The Windows test installer performs the same on-disk migration before launching Minecraft. This prevents a deleted legacy pack from surviving as a selected in-memory “ghost” next to the renamed/iconified pack.

`generated.json` is cache/output, not hand-authored configuration. The runtime rebuilds it when the material-inference schema or installed mod set changes. The 1.12.2 mod-set fingerprint is derived deterministically from Minecraft version plus installed mod archive names, sizes and modification times. If the fingerprint and schema match, startup uses the cached database instead of enumerating every registered block state again.

Generation happens after Forge block registration is complete. The runtime enumerates registered blocks/states once, precomputes material inference, writes the new JSON atomically, and populates the same state metadata cache later used by world capture. If enumeration fails and a previous generated database exists, the old database is preserved rather than overwritten with an empty/broken one.

## Inference inputs

For an unknown/modded block state the 1.12.2 adapter constructs an acoustic fingerprint from as many stable clues as are available:

```text
registry id + metadata/state id
Minecraft Material
SoundType
OreDictionary names
solid/full-cube/opaque/liquid flags
hardness
blast resistance
registry-name semantics
```

`OreDictionary` is important but is not treated as a complete physical model. `blockCopper`, `plateCopper` and `wireCopper`, for example, share chemistry but not necessarily acoustic geometry. Material/SoundType and block-form tokens therefore contribute independently.

Hardness and blast resistance are deliberately **weak modifiers**, never direct formulas for absorption. They help distinguish very soft/light versus massive/hard variants only after semantic evidence has selected a plausible material family.

The inference result includes a confidence score and human-readable reasons. Generated JSON stores these diagnostics in an `inference` array so a modpack author can audit why a block was classified as metal, wood, concrete, fabric, etc.

Current inferred families include stone, concrete, brick, ceramic, plaster, wood, metal, glass, fabric, carpet, polymer, rubber, soil, sand, foliage, liquid and air. Each resolved `AcousticMaterial` carries eight-band absorption plus scattering and transmission; reflection is energy-bounded from those values.

## Geometry is separate from material identity

A material answers **what the surface is made of**. Collision geometry answers **where that material actually exists**. RC15 therefore does not approximate every non-air block as a one-metre cube. The 1.12.2 adapter captures the actual collision AABBs produced by the current `IBlockState` at the sampled world position, including neighbour-dependent multipart geometry such as connected fences, panes/bars, stairs and modded collision shapes.

The portable scene stores an `AcousticShape` per voxel. Full cubes use a zero-allocation fast path; partial cells store one or more local `[0,1]` AABBs. CPU DDA, OpenCL rays and CUDA rays consume the same shape semantics. Direct transmission integrates the **union of occupied path intervals** inside a cell, so a thin glass pane attenuates according to its actual crossed thickness rather than acting like one metre of glass. Empty portions of slabs/stairs/fences/panes remain acoustically open to geometric/direct rays.

The bounded low-frequency FDTD grid is intentionally coarser than Minecraft collision geometry. A partial object becomes a hard FDTD boundary only when its acoustic-shape occupancy is at least 75% of the FDTD cell. Thin bars/panes therefore do not turn into artificial metre-thick low-frequency walls; their exact high-frequency/direct interaction is still handled by geometric solvers.

## Resolution priority

Effective material rules are layered deterministically. Highest precedence wins:

```text
active ordinary resource-pack acoustic overlays
legacy shader-local material files (compatibility only)
auto-generated exact state database
built-in semantic fallback rules
```

Within a layer, normal material-rule priority applies. Exact `STATE_ID` rules are indexed for fast runtime lookup but do not bypass higher-priority user overrides.

The Reference Acoustic Shader intentionally contains no first-party block material database anymore. Material data belongs to the material-resource system.

## User/resource-pack overrides

Any ordinary Minecraft 1.12.2 resource pack, directory or ZIP, may contain one or more files under:

```text
assets/<namespace>/acoustic_materials/**/*.json
```

For example:

```json
{
  "materials": {
    "mymod:foam": {
      "absorption": [0.12, 0.28, 0.55, 0.78, 0.90, 0.94, 0.96, 0.97],
      "scattering": 0.62,
      "transmission": 0.03
    }
  },
  "rules": [
    {
      "priority": 100,
      "kind": "REGISTRY_ID",
      "match": "mymod:acoustic_foam",
      "material": "mymod:foam"
    }
  ]
}
```

Supported match kinds include `STATE_ID`, `REGISTRY_ID`, `TAG`, `DICTIONARY_EXACT` and `DICTIONARY_PREFIX`. Prefer exact state/registry rules for known blocks and dictionary/tag rules for intentional families.

A complete example lives at `examples/material-resource-pack/`.

## Resource-pack selection and hot reload

Ordinary acoustic material overlays follow Minecraft's active `resourcePacks` list. The generated default pack remains underneath them regardless of vanilla selection state. This means a normal texture/resource pack can optionally bundle acoustic material corrections alongside its other assets.

The 1.12.2 runtime periodically fingerprints active acoustic material files. ZIPs use archive metadata; directory packs include the timestamps/sizes of `pack.mcmeta` and nested `assets/*/acoustic_materials/*.json`, so editing an existing JSON file is detected even when the top-level directory timestamp does not change. A valid change recompiles the material resolver transactionally. A broken overlay is ignored/diagnosed rather than corrupting the generated default database.

## Performance

World traversal never reruns OreDictionary inference per voxel. The expensive Forge/OreDictionary introspection is cached by block-state identity and the resulting generated database is exact-state indexed. During normal ray/world capture a voxel lookup is therefore a cached descriptor plus indexed material resolution rather than a registry/OreDictionary scan.

This is especially important for large 1.12.2 modpacks, where hundreds or thousands of registered states may exist but the same states appear millions of times in world geometry.

## Cross-version direction

The generated database is a platform adapter concern, not part of the portable shader ABI. A modern Minecraft adapter can replace 1.12.2 OreDictionary evidence with native block tags while producing the same portable semantic/material rules. Ordinary resource-pack acoustic overlays can keep the same `assets/<namespace>/acoustic_materials/` concept across versions, subject to future spec-version migration rules.


## Relationship to volume media and source profiles

Surface materials, volume propagation media and sound-source profiles intentionally share the ordinary Resource Pack transport but are different schemas. Surface rules live under `acoustic_materials/`; fluid/gas propagation definitions live under `acoustic_media/`; event/source metadata lives under `acoustic_sources/`. A volume medium supplies density, sound speed and eight-band bulk attenuation and is resolved independently from the block surface material. See `resource-pack-author-guide.md` and `source-profiles.md`. The platform supplies deterministic AIR/WATER/LAVA fallbacks when no explicit medium rule matches.

### RC15 migration repair

RC16 additionally repairs the malformed composite selection string `AcousticShaders-Generated-Materials Acoustic Shaders Default Materials` that an RC15 Windows PowerShell 5.1 array-coercion bug could write to `options.txt`. The installer now parses JSON arrays without coercing them to a single string and emits the JSON string array explicitly; the in-game migrator also recognizes and removes the malformed composite entry.

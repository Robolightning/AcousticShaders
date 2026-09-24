# Acoustic Resource Pack author guide

Ordinary Minecraft Resource Packs can carry **acoustic data** without containing executable code. This is the recommended way to describe modded blocks and special sound sources while keeping Acoustic Shader algorithms independent.

## Layout

```text
MyResourcePack/
  pack.mcmeta
  assets/
    mypack/
      acoustic_materials/
        materials.json
      acoustic_media/
        media.json
      acoustic_sources/
        sources.json
```

All three trees are optional. A texture/resource pack may contain textures, sounds and acoustic data together.

Acoustic Shaders always supplies `resourcepacks/Acoustic Shaders Default Materials` as the lowest-priority generated base. Do not edit that generated pack: put corrections in your own Resource Pack.

## Block materials

A material entry defines eight-band absorption plus scattering/transmission. Example:

```json
{
  "materials": {
    "mypack:rubber_panel": {
      "absorption": [0.08,0.10,0.14,0.20,0.31,0.45,0.58,0.66],
      "scattering": 0.35,
      "transmission": 0.05
    }
  },
  "rules": [
    {
      "priority": 500,
      "kind": "REGISTRY_ID",
      "match": "examplemod:rubber_panel",
      "material": "mypack:rubber_panel"
    }
  ]
}
```

Supported 1.12.2 rule kinds include `STATE_ID`, `REGISTRY_ID`, `TAG`, `DICTIONARY_EXACT` and `DICTIONARY_PREFIX`. Prefer exact state/registry rules for important blocks and OreDictionary rules for broad mod interoperability.

See `material-database.md` for generated inference, semantic families and coefficient details.

## Volume propagation media

Use `acoustic_media/**/*.json` for the fluid or gas filling space. This is intentionally separate from `acoustic_materials`: the material answers “what happens at this surface?”, while the medium answers “how fast and how strongly does pressure propagate through this volume?”.

Example modded oil:

```json
{
  "media": {
    "example:oil": {
      "density_kg_m3": 850.0,
      "speed_m_s": 1320.0,
      "attenuation_db_per_km": [4, 5, 7, 10, 16, 28, 50, 90]
    }
  },
  "rules": [
    { "kind": "GLOB", "match": "example:*oil*", "medium": "example:oil", "priority": 100 }
  ]
}
```

Allowed matching kinds are `STATE_ID`, `REGISTRY_ID`, `GLOB`, `TAG`, `DICTIONARY_EXACT` and `DICTIONARY_PREFIX`. Eight-band loss may be supplied as either `attenuation_db_per_km` or `absorption_nepers_per_meter`. Density and sound speed must be positive finite values.

On Minecraft 1.12.2 the platform still infers AIR/WATER/LAVA when no explicit rule matches. A modded-fluid rule can override that fallback without changing the block's surface material. Partial fluid fill is represented separately from solid collision geometry, so a block may contain both air and a custom liquid medium. Simple bottom-up fills may be neighbor-smoothed by the runtime into a continuous bilinear free surface; custom multipart medium shapes remain authoritative and use the exact-shape fallback.

## Sound-source profiles

Source profiles describe the physical behavior of an emitter/event rather than a surface. Example projectile:

```json
{
  "profiles": {
    "mypack:plasma_bolt": {
      "category": "projectile",
      "emission": [0.25,0.35,0.55,0.90,1.25,1.55,1.70,1.50],
      "direct": 1.0,
      "occlusion": 0.90,
      "diffraction": 0.75,
      "early_reflections": 0.55,
      "late_reverb": 0.28,
      "priority": 1.50,
      "movement_sensitivity": 3.0,
      "doppler": 1.20,
      "transient": 1.10,
      "bypass": false
    }
  },
  "rules": [
    {"priority": 500, "kind":"GLOB", "match":"*plasma_bolt*", "profile":"mypack:plasma_bolt"}
  ]
}
```

Recommended categories are semantic labels, not hardcoded algorithms. The generated fallback currently includes `generic`, `explosion`, `projectile`, `impact`, `footstep`, `machine`, `weather` and `nonspatial`.

Use `EXACT`/`PREFIX` for important known assets and `GLOB`/`CONTAINS` only when filenames are reliably semantic. See `source-profiles.md` for every field and runtime behavior.

## Layering

For materials, volume media and source profiles, the practical precedence is:

```text
active ordinary Resource Packs (user/modpack data)
    > generated Acoustic Shaders Default Materials
    > conservative built-in fallback
```

Legacy shader-local material rules remain supported for compatibility, but new packs should not couple physical block/source databases to a shader algorithm.

If multiple active Resource Packs match the same thing, the runtime preserves deterministic resource-pack/layer priority and then rule priority. Use intentionally high rule priorities for explicit mod corrections rather than relying on filename order.

## Hot reload

Directory Resource Packs are content-fingerprinted. Editing `acoustic_materials/**/*.json`, `acoustic_media/**/*.json` or `acoustic_sources/**/*.json` is detected without restarting Minecraft. Replacement is transactional: invalid JSON/rules are rejected and the previous working resolver remains active.

ZIP Resource Packs are reloaded when the ZIP changes. For active authoring, an unpacked directory is more convenient.

## Generated database and mod changes

The generated base is rebuilt when the installed-mod/runtime inference fingerprint changes. On 1.12.2 it precomputes block-state classification using registry/state identity, Minecraft Material, SoundType, OreDictionary, structural flags and weak hardness/resistance hints. The resulting exact-state rules are cached so normal world traversal does not repeatedly inspect OreDictionary.

Source defaults are generated from the runtime source-profile schema and are updated when that schema changes.

## Choosing where a feature belongs

Use this rule of thumb:

- **Resource Pack / material**: “what is this surface physically?”
- **Resource Pack / volume medium**: “what fluid/gas fills this space, at what density/sound speed, and with what bulk loss?”
- **Resource Pack / source profile**: “what kind of emitter/event is this sound?”
- **Acoustic Shader Pack**: “how do rays, waves, diffraction, reflections, RIR/HRTF/DSP render those physical inputs?”
- **trusted extension mod**: “I need a brand-new executable algorithm or platform event emitter.”

For example, a TNT explosion's low-frequency-heavy source spectrum belongs in `acoustic_sources`; concrete wall absorption belongs in `acoustic_materials`; how the explosion excites wave/ray response belongs in the Acoustic Shader.

## Projectile limitation

A source profile is data and cannot create entity behavior by itself. If a vanilla/modded projectile emits a moving sound, its profile can request high movement sensitivity and Doppler. RC20's Minecraft 1.12.2 adapter additionally derives the built-in `acousticshaders:projectile.flight` sound for supported vanilla projectile families, but Resource Pack metadata alone still does not synthesize arbitrary mod-projectile audio. Third-party projectile subclasses are left to their own sound implementation by default to avoid duplicates.

## Validation workflow

1. Start with the generated database and Reference Acoustic Shader.
2. Enable an unpacked Resource Pack containing one exact override.
3. Inspect the runtime diagnostics to confirm the observed block/source identifier.
4. Edit the JSON and verify hot reload.
5. Only then generalize with dictionary/glob rules.

The repository includes `examples/material-resource-pack/` as a combined material + source-profile example.

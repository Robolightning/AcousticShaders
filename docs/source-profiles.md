# Acoustic source profiles

Acoustic Shaders separates **what a sound source physically is** from **how an Acoustic Shader renders it**.

Block materials describe the environment. Source profiles describe emitters/events such as explosions, projectiles, impacts, footsteps, machines and weather. A source profile is portable data; it does not execute code and it does not replace the sound asset itself.

## Data ownership

The recommended split is:

- ordinary Minecraft **Resource Packs** define or override source classification/physical metadata under `assets/<namespace>/acoustic_sources/**/*.json`;
- an **Acoustic Shader Pack** contains `standard.source_behavior` and decides whether/how strongly to use those profiles;
- the generated `Acoustic Shaders Default Materials` resource pack always supplies conservative first-party fallback profiles at the bottom of the stack.

This means a modpack/resource-pack author can correct a mod sound without forking the acoustic shader, while a shader author can disable category processing or choose a different renderer.

## Default categories

The generated base includes conservative fallback categories:

- `generic` — neutral world source;
- `explosion` — low-frequency-heavy transient, stronger room excitation and diffraction, high perceptual priority;
- `projectile` — moving/fly-by source, reduced late tail, higher movement update sensitivity and Doppler enabled;
- `impact` — short transient with moderate room response;
- `footstep` — lower-priority short source with controlled late tail;
- `machine` — persistent mechanical source;
- `weather` — diffuse/outdoor-biased source with weak local reverb;
- `nonspatial` — music/UI-style source that bypasses world acoustics.

These are fallbacks, not claims that every mod sound can be perfectly identified from its filename. Exact resource-pack rules should be preferred for important modded sounds.

## Resource-pack layout

Any enabled ordinary Minecraft resource pack may contain:

```text
assets/<namespace>/acoustic_sources/*.json
assets/<namespace>/acoustic_sources/**/*.json
```

Example:

```json
{
  "profiles": {
    "example:plasma_bolt": {
      "category": "projectile",
      "emission": [0.25, 0.35, 0.55, 0.90, 1.25, 1.55, 1.70, 1.50],
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
    {
      "priority": 200,
      "kind": "GLOB",
      "match": "*plasma_bolt*",
      "profile": "example:plasma_bolt"
    }
  ]
}
```

A complete block-material + source-profile example is packaged as `AcousticData-Example-ResourcePack.zip` and lives in `examples/material-resource-pack/` in the source tree.

## Profile fields

`emission` has the same eight octave bands used by the material system:

```text
125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz
```

Values are relative spectral-energy multipliers in `[0,4]`. They are not an equalizer applied destructively to the original audio asset; they describe the source spectrum available to propagation/backends. The current legacy EFX projection uses low/high aggregates, while the portable RIR path uses the profile when weighting direct/early/late energy.

Other fields:

| Field | Meaning |
| --- | --- |
| `category` | stable semantic label for shader/debug/UI use |
| `direct` | relative direct-path contribution |
| `occlusion` | exponent-like occlusion strength; `<1` leaks more energy through/around obstruction, `>1` attenuates more strongly |
| `diffraction` | relative diffracted-path contribution |
| `early_reflections` | early reflection energy multiplier |
| `late_reverb` | late-field/reverb energy multiplier |
| `priority` | perceptual scheduler importance multiplier |
| `movement_sensitivity` | how aggressively a moving source is re-evaluated; projectiles normally use `>1` |
| `doppler` | OpenAL/source-velocity Doppler scale where the platform backend supports it |
| `transient` | temporal-importance hint used by scheduling/renderer decisions |
| `bypass` | bypass world acoustics; appropriate for menu/UI/music-style sources |

Relative coefficients are intentionally bounded. A source profile must not be able to create unbounded gain or arbitrary executable behavior.

## Matching rules

Supported source rule kinds are:

- `EXACT` — exact normalized source identifier;
- `PREFIX` — identifier starts with the match string;
- `CONTAINS` — identifier contains the match string;
- `GLOB` — `*` and `?` wildcard matching.

Matching is case-insensitive after normalizing `\\` to `/`.

On Minecraft 1.12.2 the Paulscode bridge prefers `Source.filenameURL.getFilename()` as the stable identifier. Depending on the resource system/mod, this can look like a resource path such as `minecraft/sounds/entity/arrow/...ogg`. If unavailable, the Paulscode source name is a fallback and may be less semantically useful. For important mod sounds, use diagnostic logs to inspect the observed identifier before writing an exact rule.

Higher rule priority wins. Ordinary active resource packs are layered above the generated fallback pack, so user/modpack corrections override built-in heuristics.

## Shader controls

The Reference Acoustic Shader includes `standard.source_behavior` before propagation. Its user options are:

```text
SOURCE_PROFILES=ON/OFF
SOURCE_PROFILE_STRENGTH=0..1
SOURCE_DOPPLER=ON/OFF
```

`SOURCE_PROFILE_STRENGTH=0` is acoustically neutral. `1` uses the resource profile fully. Lower presets may reduce the strength but retain the feature. A third-party Acoustic Shader may omit `standard.source_behavior`; in that case source resource metadata remains available to the runtime but is not applied by that shader.

The source profile is a typed pipeline resource (`source.profile`); the shader-produced effective result is `source.behavior`. This keeps classification/data separate from processing.

## Real-time behavior in Minecraft 1.12.2

The legacy bridge uses source metadata in several places:

1. perceptual source budgeting (`priority`, `transient`);
2. moving-source re-evaluation threshold (`movement_sensitivity`);
3. direct occlusion/diffraction and wet-field projection;
4. spectral low/high weighting for the EFX fallback;
5. OpenAL source velocity for optional Doppler on moving profiles.

The direct sound is still started immediately by Minecraft/Paulscode; expensive propagation is asynchronous and later updates the acoustic projection.

### Projectile emitters and source-profile scope

Source profiles still describe **how an existing/derived sound is processed**; they do not themselves execute entity logic or synthesize arbitrary mod sounds. The Minecraft 1.12.2 adapter now supplies a bounded derived `acousticshaders:projectile.flight` `MovingSound` for supported vanilla projectile families (arrows, throwable entities, fireballs, llama spit and shulker bullets). That ordinary Minecraft sound then resolves the `projectile` source profile and traverses the normal SoundHandler -> Paulscode -> Mixin -> `LegacySoundHook` -> Acoustic Shaders path. Third-party projectile subclasses are intentionally not given a synthetic duplicate by default, so mod-provided flight audio remains authoritative.

## Hot reload and generated defaults

`Acoustic Shaders Default Materials` now contains both:

```text
assets/acousticshaders/acoustic_materials/generated.json
assets/acousticshaders/acoustic_sources/generated.json
```

The source fallback file is maintained by Acoustic Shaders and updated when the built-in source-profile schema changes. Do not edit it directly. Put overrides into your own resource pack.

Active directory resource packs are content-fingerprinted, so editing an `acoustic_sources/*.json` file is picked up by the same transactional resource hot-reload mechanism used by material JSON. Invalid content is rejected without replacing the last working resolver.

## Authoring guidance

Prefer this order:

1. exact/prefix rule for a known important mod sound;
2. semantic glob rule for a well-named sound family;
3. generated generic heuristic only as fallback.

Do not classify by loudness alone. An explosion and a machine may have similar volume while requiring very different transient, reverb and movement behavior. Keep physical metadata in Resource Packs and algorithmic policy in Acoustic Shader Packs.

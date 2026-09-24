package dev.acoustic.core.source.profile

/** Built-in fallback source categories written into the generated acoustic resource pack. */
object DefaultSourceProfiles {
    const val SCHEMA_VERSION = 1

    @JvmStatic
    fun json(): String = """
{
  "profiles": {
    "acoustic:generic": {"category":"generic","emission":[1,1,1,1,1,1,1,1]},
    "acoustic:explosion": {"category":"explosion","emission":[1.8,1.6,1.35,1.1,0.9,0.75,0.65,0.55],"occlusion":0.68,"diffraction":1.2,"early_reflections":1.12,"late_reverb":1.38,"priority":2.4,"transient":1.15},
    "acoustic:projectile_launch": {"category":"projectile_launch","emission":[0.9,1.0,1.15,1.2,1.15,1.0,0.85,0.7],"occlusion":0.9,"diffraction":0.82,"early_reflections":0.68,"late_reverb":0.42,"priority":1.7,"movement_sensitivity":1.0,"doppler":0.0,"transient":1.25},
    "acoustic:projectile_flight": {"category":"projectile_flight","emission":[0.55,0.72,0.95,1.2,1.35,1.3,1.1,0.85],"occlusion":0.9,"diffraction":0.78,"early_reflections":0.58,"late_reverb":0.32,"priority":1.6,"movement_sensitivity":2.8,"doppler":1.0,"transient":0.9},
    "acoustic:projectile": {"category":"projectile","emission":[0.65,0.8,1.0,1.2,1.3,1.2,1.0,0.8],"occlusion":0.9,"diffraction":0.78,"early_reflections":0.62,"late_reverb":0.38,"priority":1.6,"movement_sensitivity":2.8,"doppler":1.0,"transient":1.1},
    "acoustic:impact": {"category":"impact","emission":[1.15,1.1,1.05,1,0.95,0.9,0.85,0.8],"early_reflections":0.9,"late_reverb":0.72,"priority":1.25,"transient":1.2},
    "acoustic:footstep": {"category":"footstep","emission":[1.15,1.15,1.05,0.95,0.85,0.75,0.65,0.55],"early_reflections":0.78,"late_reverb":0.58,"priority":0.9,"transient":1.05},
    "acoustic:machine": {"category":"machine","emission":[1.2,1.15,1.1,1.0,0.9,0.85,0.8,0.75],"early_reflections":0.88,"late_reverb":0.82,"priority":0.95},
    "acoustic:weather": {"category":"weather","emission":[0.8,0.85,0.9,1.0,1.1,1.15,1.1,1.0],"direct":0.95,"early_reflections":0.35,"late_reverb":0.22,"priority":0.65},
    "acoustic:nonspatial": {"category":"nonspatial","emission":[1,1,1,1,1,1,1,1],"bypass":true,"priority":0.2}
  },
  "rules": [
    {"priority":120,"kind":"GLOB","match":"*explosion*","profile":"acoustic:explosion"},
    {"priority":118,"kind":"GLOB","match":"*explode*","profile":"acoustic:explosion"},
    {"priority":116,"kind":"GLOB","match":"*tnt*","profile":"acoustic:explosion"},
    {"priority":116,"kind":"GLOB","match":"*arrow*hit*","profile":"acoustic:impact"},
    {"priority":115,"kind":"GLOB","match":"*bowhit*","profile":"acoustic:impact"},
    {"priority":114,"kind":"GLOB","match":"*arrow*shoot*","profile":"acoustic:projectile_launch"},
    {"priority":113,"kind":"GLOB","match":"*random/bow*","profile":"acoustic:projectile_launch"},
    {"priority":112,"kind":"GLOB","match":"*projectile.flight","profile":"acoustic:projectile_flight"},
    {"priority":112,"kind":"GLOB","match":"*projectile.flight.ogg","profile":"acoustic:projectile_flight"},
    {"priority":112,"kind":"GLOB","match":"*projectile/flight.ogg","profile":"acoustic:projectile_flight"},
    {"priority":111,"kind":"GLOB","match":"*arrow*fly*","profile":"acoustic:projectile_flight"},
    {"priority":110,"kind":"GLOB","match":"*arrow*","profile":"acoustic:projectile"},
    {"priority":108,"kind":"GLOB","match":"*projectile*","profile":"acoustic:projectile"},
    {"priority":106,"kind":"GLOB","match":"*bullet*","profile":"acoustic:projectile"},
    {"priority":104,"kind":"GLOB","match":"*rocket*","profile":"acoustic:projectile"},
    {"priority":100,"kind":"GLOB","match":"*impact*","profile":"acoustic:impact"},
    {"priority":98,"kind":"GLOB","match":"*hit*","profile":"acoustic:impact"},
    {"priority":90,"kind":"GLOB","match":"*step*","profile":"acoustic:footstep"},
    {"priority":80,"kind":"GLOB","match":"*machine*","profile":"acoustic:machine"},
    {"priority":78,"kind":"GLOB","match":"*engine*","profile":"acoustic:machine"},
    {"priority":70,"kind":"GLOB","match":"*rain*","profile":"acoustic:weather"},
    {"priority":68,"kind":"GLOB","match":"*weather*","profile":"acoustic:weather"},
    {"priority":200,"kind":"GLOB","match":"*music*","profile":"acoustic:nonspatial"},
    {"priority":198,"kind":"GLOB","match":"*record*","profile":"acoustic:nonspatial"},
    {"priority":196,"kind":"GLOB","match":"*gui*","profile":"acoustic:nonspatial"}
  ]
}
""".trimStart()
}

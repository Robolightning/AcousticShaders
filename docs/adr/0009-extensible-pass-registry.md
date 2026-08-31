# ADR-0009: Extensible pass registry

Status: Accepted.

The pipeline compiler resolves declarative pass IDs through `PassFactoryRegistry`; it does not hard-code a closed algorithm list. Extension mods can provide new solvers while packs remain declarative. This prevents the specification from imposing a ceiling on future ray, wave, neural, GPU or native techniques.

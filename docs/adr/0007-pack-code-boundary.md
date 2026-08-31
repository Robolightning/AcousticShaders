# ADR-0007: Shader packs are declarative, not arbitrary JVM code

Status: Accepted

Normal acoustic shader packs contain manifests, pipeline descriptions, options, material definitions and future sandboxed compute programs. They do not execute arbitrary Java classes. New native/CPU algorithms are supplied by explicit extension mods that register capabilities/passes with the runtime.

This keeps installing an acoustic shader pack closer to installing a visual shader pack rather than executing an unknown mod jar.

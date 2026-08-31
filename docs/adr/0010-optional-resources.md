# ADR-0010: Optional resource dependencies

Status: Accepted.

Passes may declare `optionalReads`. A dependency edge exists only when an enabled producer exists. This allows a hybridizer to consume a wave field at HIGH/ULTRA while remaining valid when LOW disables the wave pass.

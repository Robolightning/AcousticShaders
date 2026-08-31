# ADR-0012: RIR as a portable output

Status: Accepted.

The standard hybrid pipeline can synthesize an impulse response resource (`rir.mono`). A mono RIR is deliberately only the first portable output representation. `standard.foa` now derives a first-order Ambisonics ACN/SN3D resource (`rir.foa.acn_sn3d`) without removing the hybrid response or mono RIR beneath it. HRTF providers and richer multichannel representations can consume or replace the decode stage without changing propagation solvers.

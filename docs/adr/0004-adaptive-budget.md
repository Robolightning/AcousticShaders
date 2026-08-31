# ADR-0004: Budget-based adaptive quality

Status: Accepted

## Decision

Presets map to initial quality parameters and budgets; runtime adaptation reacts to measured pass cost rather than treating `Ultra` as a permanently fixed ray count. Quality changes use smoothing/hysteresis.

## Rationale

Audio simulation cost changes radically with scene complexity and active sources. A time budget gives more stable game performance and makes heterogeneous CPU/GPU backends possible.

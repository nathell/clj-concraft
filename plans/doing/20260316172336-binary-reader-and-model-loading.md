# Binary Reader and Model Loading

## Scope
Phase 0-1: binary format reader primitives, tagset deserialization, validate by reading model header.

## Steps
1. Set up deps.edn and src directory structure
2. Implement `concraft.binary` — Haskell Data.Binary reader primitives
3. Validate by reading model version string
4. Implement `concraft.tagset` — positional tagset reader
5. Validate tagset round-trip

# Objective

This is clj-concraft, a reimplementation in Clojure of Concraft and Concraft-pl – a morphological tagger of Polish based on constrained conditional random fields.

The paper describing Concraft and the math behind it can be found in this repo in `doc/coling2012.pdf`.

The original libraries are written in Haskell – their source can be found in `concraft-pl/` and `concraft/`, respectively. (Note these are symlinks.)

# Validation

There is a compiled binary in the concraft-pl directory, as well as a model file. You can assume the binary is running in server mode, and you can run Concraft-pl as follows:

```
./concraft-pl/concraft-pl client < concraft-pl/small-input.dag
```

clj-concraft, when ready, should produce the same output, given the same input. There is also a larger sample input, concraft-pl/example-input.dag, for cross-validation.

# How to work

- Plan ahead and iterate in small steps. Subdivide big chunks of work into manageable steps.
- Document your approach and findings.
- Keep a backlog of work items. See the `Plans` section below.
- When in doubt, stop and ask questions. Your human operator isn't the author of Concraft, but he should be nevertheless able to answer questions about context of its usage.

# Plans

- **`doing/`** — work queue. Pick a task, do the work, move it to `done/` when finished.
- **`done/`** — archive of completed work.
- Files are named with Zettelkasten timestamps: `YYYYMMDDHHmmss-<slug>.md` (e.g., `20260220183612-block-scoping.md`). Timestamps eliminate merge conflicts when multiple branches complete plans concurrently.

## Tools

- **`scripts/plan-create <name>`** — Creates a new plan file in `plans/doing/` with a timestamped prefix and a title header. Prints the created path to stdout.
- **`scripts/plan-done <name>`** — Moves a plan from `plans/doing/` to `plans/done/` with a fresh timestamp reflecting completion time. Accepts a slug (`arrays`), `slug.md`, or full filename. Prints the new path to stdout.

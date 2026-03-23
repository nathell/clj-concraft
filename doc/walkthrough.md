# Walkthrough: Tagging "Zatrzasnął drzwi od mieszkania"

This document traces, step by step, what clj-concraft does when it processes the sentence _Zatrzasnął drzwi od mieszkania_ ("He slammed the door of the apartment"). The input comes from `small-input.dag`.

## The input

The input is a DAG in tab-separated format. Each line describes one possible morphosyntactic interpretation of a word. Multiple lines with the same (tail-node, head-node) pair form a single edge with multiple interpretations.

```
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m1:perf   0.000
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m2:perf   0.000
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m3:perf   0.000
1  2  drzwi       drzwi       subst:pl:nom:n:pt   0.000
1  2  drzwi       drzwi       subst:pl:acc:n:pt   0.000
1  2  drzwi       drzwi       subst:pl:voc:n:pt   0.000
1  2  drzwi       drzwi       subst:pl:gen:n:pt   0.000
2  3  od          od          prep:gen:nwok        0.000
2  3  od          oda         subst:pl:gen:f       0.000
3  4  mieszkania  mieszkanie  subst:sg:gen:n:ncol  0.000
3  4  mieszkania  mieszkanie  subst:pl:nom:n:ncol  0.000
3  4  mieszkania  mieszkanie  subst:pl:acc:n:ncol  0.000
3  4  mieszkania  mieszkanie  subst:pl:voc:n:ncol  0.000
3  4  mieszkania  mieszkać    ger:pl:nom:n:imperf:aff  0.000
3  4  mieszkania  mieszkać    ger:pl:acc:n:imperf:aff  0.000
3  4  mieszkania  mieszkać    ger:sg:gen:n:imperf:aff  0.000
```

This represents the output of a morphological analyzer (Morfeusz). Each word has several possible analyses — for example, _Zatrzasnął_ could be masculine personal (m1), masculine animate (m2), or masculine inanimate (m3). The probabilities are all 0.000 because the analyzer doesn't assign probabilities — that's the tagger's job.

Since this sentence has no segmentation ambiguity (each word spans exactly one edge), the DAG is a simple chain:

```
node 0 —[edge 0: Zatrzasnął]-> node 1 —[edge 1: drzwi]-> node 2 —[edge 2: od]-> node 3 —[edge 3: mieszkania]-> node 4
```

**Code**: Parsing is handled by `concraft.format/parse-data` ([`src/concraft/format.clj`](../src/concraft/format.clj)), which splits text into paragraphs, then calls `parse-sent` -> `rows->dag`. The rows are grouped by `(tail-node, head-node)` using a `LinkedHashMap` (to preserve insertion order), then each group becomes an edge in the DAG built by `concraft.dag/from-edges` ([`src/concraft/dag.clj`](../src/concraft/dag.clj)). Each edge label is a Seg containing `{:word {:orth, :known}, :tags {Interp -> weight}}`.

## Step 0: Model loading

Before tagging, the model is loaded from a gzip-compressed binary file (~84 MB). It contains:

- **Tagset**: 40 parts of speech, 14 grammatical attributes with their possible values
- **Guesser** (CRF chain1): for predicting tags of unknown words — 103,702 parameters
- **Segmenter** (CRF chain2 tiers): for detecting sentence boundaries — 280,659 parameters, 1 tier (`{pos, eos}`)
- **Disambiguator** (CRF chain2 tiers): for selecting the best tag in context — 6,437,169 parameters, 2 tiers (`{pos, case, person}` and `{number, gender, degree, aspect, ...}`)

The binary format uses Haskell's `Data.Binary` serialization, with doubles encoded via the pre-0.8 `decodeFloat` representation (25 bytes per double instead of the usual 8-byte IEEE 754).

**Code**: `concraft.model/load-model` ([`src/concraft/model.clj`](../src/concraft/model.clj)) opens a `GZIPInputStream`, reads the version string, tagset, guessNum, then delegates to sub-readers:
- `concraft.tagset/read-tagset` ([`src/concraft/tagset.clj`](../src/concraft/tagset.clj)) for the positional tagset
- `concraft.schema/read-schema-conf` ([`src/concraft/schema.clj`](../src/concraft/schema.clj)) for feature configuration
- `concraft.crf.chain1/read-crf` ([`src/concraft/crf/chain1.clj`](../src/concraft/crf/chain1.clj)) for the guesser CRF
- `concraft.crf.chain2/read-crf` ([`src/concraft/crf/chain2.clj`](../src/concraft/crf/chain2.clj)) for segmenter and disambiguator CRFs

All primitive binary reading (Int64, Double via `decodeFloat`, String, Text, List, Map, etc.) lives in `concraft.binary` ([`src/concraft/binary.clj`](../src/concraft/binary.clj)).

## Step 1: Guessing (CRF chain1)

**Purpose**: Assign prior probabilities to each interpretation, informed by word shape and context.

Even though all four words in this sentence are known (they appear in the morphological dictionary), the guesser still runs on them. For known words, the guesser's output becomes the initial weight for each tag.

**Code**: The entry point is `concraft.polish/anno-all` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)), which calls `concraft.guesser/guess-marginals` ([`src/concraft/guesser.clj`](../src/concraft/guesser.clj)).

### 1a. Feature extraction

For each edge, the guesser schema extracts observations based on the word's surface form:

| Edge | Word | Observations |
|------|------|-------------|
| 0 | Zatrzasnął | `z`, `za`, `ł`, `ął`, `T`, `T-ul` |
| 1 | drzwi | `d`, `dr`, `i`, `wi`, `T`, `F-l` |
| 2 | od | `o`, `od`, `d`, `od`, `T`, `F-l` |
| 3 | mieszkania | `m`, `mi`, `a`, `ia`, `T`, `F-l` |

The six features per word are:
1. **Lowercase prefix of length 1** (e.g., `z`)
2. **Lowercase prefix of length 2** (e.g., `za`)
3. **Lowercase suffix of length 1** (e.g., `ł`)
4. **Lowercase suffix of length 2** (e.g., `ął`)
5. **Known word?** — `T` (true) for all words here
6. **Beginning + packed shape** — `T-ul` for the first word (sentence-beginning = `T`, shape `ul` = uppercase+lowercase), `F-l` for others

Each observation is paired with an index (like `[1]`, `[2]`, ...) and looked up in the observation codec to get an internal integer ID.

**Code**: `concraft.schema/from-conf` ([`src/concraft/schema.clj`](../src/concraft/schema.clj)) builds a schema function from the `SchemaConf`. It chains up to 8 feature blocks (`low-prefixes-block`, `low-suffixes-block`, `known-block`, `beg-packed-block`, etc.), each of which generates observation values. The `ox-exec` function assigns sequential indices starting from `[1]`, mirroring Haskell's `Ox` monad. The `shift` function navigates the DAG for context windows (e.g., looking at the previous or next word). `schematize` applies the schema to every edge in the DAG.

### 1b. Sentence encoding

Each edge's observations are mapped to internal IDs via the observation codec. The label set for each edge is constrained to the morphological interpretations present in the input (e.g., edge 0 can only be `praet:sg:m1:perf`, `praet:sg:m2:perf`, or `praet:sg:m3:perf`).

**Code**: `concraft.crf.chain1/encode-sent` ([`src/concraft/crf/chain1.clj`](../src/concraft/crf/chain1.clj)). For each edge, it maps external observations `([Int], Text)` to internal `Ob` IDs via the observation codec's `to` map, and maps external tags to internal `Lb` IDs via the label codec (wrapping in `Maybe` since the codec uses `Maybe Tag`). For known words, the label set is constrained; for OOV words, `:lbs` is `nil`, which causes the inference to use `r0` (the default unconstrained label set of 288 labels).

### 1c. Forward-backward algorithm

The CRF chain1 is a first-order linear-chain conditional random field. It computes:

- **Observation potential** ψ(edge, label) — the product of feature weights for observation features matching this (edge, label) pair
- **Forward values** α(edge, label) — cumulative probability of reaching this edge with this label, considering all preceding edges
- **Backward values** β(edge, label) — cumulative probability of reaching the end from this edge with this label
- **Partition function** Z = Σ α(final, label) — normalization factor
- **Marginal probability** P(label | sentence) = α(edge, label) × β(edge, label) / Z

All arithmetic is in log-domain for numerical stability: multiplications become additions, and summations use the log-sum-exp trick.

**Code**: The core inference functions are in `concraft.crf.chain1` ([`src/concraft/crf/chain1.clj`](../src/concraft/crf/chain1.clj)):
- `compute-psi` computes ψ by intersecting each observation's `(Lb, FeatIx)` pairs with the edge's allowed labels, summing the corresponding log-weights from the `values` array.
- `forward` iterates edges in topological order. For initial edges, α = ψ × start-feature weight. For others, it sums `α(prev, k) × T(k->j)` over all predecessor labels `k`, where `T` is the transition weight (looked up via `intersect-sorted` against `prev-ixs-v`). All in log-domain via `log-sum-exp`.
- `backward` is symmetric, iterating in reverse order, using `next-ixs-v` for transition weights.
- `marginals` combines α, β, and Z to produce per-edge, per-label probabilities.

### 1d. Guesser output

The marginal probabilities from the guesser:

| Edge | Word | Top interpretation | Probability |
|------|------|--------------------|-------------|
| 0 | Zatrzasnął | praet:sg:**m1**:perf | 0.9994 |
| 1 | drzwi | subst:pl:**acc**:n:pt | 0.9999 |
| 2 | od | prep:gen:nwok | 1.0000 |
| 3 | mieszkania | subst:sg:**gen**:n:ncol | 0.9919 |

These probabilities become the weights of the tags going forward. Note that the guesser already strongly favors the correct analysis based on word shape alone — suffixes like _-ął_ are very characteristic of `praet:sg:m1`.

**Code**: The marginals are decoded from internal label IDs back to external `P.Tag` values via the label codec's `from` map in `concraft.guesser/guess-marginals` ([`src/concraft/guesser.clj`](../src/concraft/guesser.clj)). Then `concraft.polish/inject-guesses` replaces each edge's tag weights with the guesser probabilities (for known words) or the top-k guesses (for OOV words).

## Step 2: Add EOS markers

**Purpose**: Prepare for sentence-boundary detection.

Each interpretation on each edge is duplicated into two variants: one with `eos=false` (not end of sentence) and one with `eos=true` (end of sentence). The `eos=true` variant gets weight 0; the `eos=false` variant keeps the original weight.

After this step, each edge has twice as many tags:

| Edge | Word | Tags before | Tags after |
|------|------|------------|------------|
| 0 | Zatrzasnął | 3 | 6 (3 × eos=false + 3 × eos=true) |
| 1 | drzwi | 4 | 8 |
| 2 | od | 2 | 4 |
| 3 | mieszkania | 7 | 14 |

**Code**: `concraft.polish/add-eos-markers` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)). For each known-word edge, it maps over the tag map and produces two entries per tag: one `(assoc interp :eos false)` with the original weight, and one `(assoc interp :eos true)` with weight 0.

## Step 3: Segmentation (CRF chain2 tiers)

**Purpose**: Decide which word ends the sentence.

The segmenter is a second-order CRF with a single tier that includes both the POS and the EOS flag. It looks at features in a wider context window (positions -1, 0, +1) than the guesser:

- **Lowercase prefixes** of length 1, 2 at positions -1, 0, +1
- **Beginning + packed shape** at positions -1, 0, +1

### 3a. Viterbi decoding

The segmenter uses `fast-tag` (Viterbi algorithm) to find the globally optimal label sequence. This is a second-order model, meaning transition features consider the current label, the previous label, AND the label two positions back.

The Viterbi path selects:

| Edge | Word | Selected label |
|------|------|----------------|
| 0 | Zatrzasnął | praet, **eos=false** |
| 1 | drzwi | subst, **eos=false** |
| 2 | od | prep, **eos=false** |
| 3 | mieszkania | subst, **eos=true** |

The segmenter correctly identifies "mieszkania" as the last word in the sentence.

**Code**: `concraft.disamb/disamb-best` ([`src/concraft/disamb.clj`](../src/concraft/disamb.clj)) is called with the segmenter model. It:
1. Calls `concraft.crf.chain2/encode-sent` ([`src/concraft/crf/chain2.clj`](../src/concraft/crf/chain2.clj)) to encode observations via the codec and split tags into tier atoms via `concraft.positional/split-tag` ([`src/concraft/positional.clj`](../src/concraft/positional.clj)). For the segmenter's single tier `{withPos=true, withEos=true}`, each tag becomes an atom like `{:pos "praet", :eos false}`.
2. Calls `concraft.crf.chain2/fast-tag` — the Viterbi implementation. This does a max-product forward pass storing backpointers (best previous state at each `(u, v)` position pair), then backtracks from the `:end` sentinel to recover the optimal path. The forward recurrence is `α(u,v) = max_w { α(v,w) × ψ(u) × transition(u,v,w) }`, where `on-word` computes ψ from the `OMap` in each `LayerMap`, and `on-transition` looks up `TFeat1`/`TFeat2`/`TFeat3` features in the `t1-map`/`t2-map`/`t3-map` arrays.
3. Maps the Viterbi result back to original interps: each interp's atoms are compared to the Viterbi-chosen atoms at its edge.

### 3b. EOS resolution

The `resolve-eos` function examines the Viterbi output: if all selected interpretations on an edge have `eos=true`, the entire edge is marked as EOS. For edge 3, all four selected `subst` variants have `eos=true`, so the edge is resolved as EOS. All other edges are resolved as non-EOS.

**Code**: `concraft.polish/resolve-eos` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)). It filters the disamb map for selected (true) interps, checks if all have `:eos true`, then applies the result uniformly to all interps on that edge.

### 3c. Sentence splitting

Since edge 3 is the final edge AND has `eos=true`, there's no split point (you only split when a non-final edge has EOS). The paragraph remains a single sentence.

**Code**: `concraft.polish/segment` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)). It finds edges with `eos=true`, identifies split nodes (head nodes of non-final EOS edges), then partitions the edge list at those nodes, building sub-DAGs via `dag/from-edges`.

## Step 4: Disambiguation (CRF chain2 tiers)

**Purpose**: Select the most likely interpretation for each word, considering sentence context.

### 4a. EOS stripping

Before running the disambiguator, the EOS variants are collapsed: `eos=true` and `eos=false` versions of the same tag are merged (keeping the higher weight). This restores the original tag count per edge.

**Code**: In `concraft.polish/anno-all` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)), the `clean-dag` is built by reducing over each edge's tags, merging EOS variants via `(assoc interp :eos false)` and taking the `max` weight.

### 4b. Feature extraction

The disambiguator uses a different set of features focused on lexical context:

- **Lowercase word form** at positions -2, -1, 0, +1
- **Lowercase prefixes** of length 1, 2, 3 at position 0 (OOV words only)
- **Lowercase suffixes** of length 1, 2, 3 at position 0 (OOV words only)
- **Beginning + packed shape** at position 0 (OOV words only)

Since all words in this sentence are known, only the lowercase word forms are used:

| Edge | Word | Observations |
|------|------|-------------|
| 0 | Zatrzasnął | `zatrzasnął`, `drzwi` (self and +1) |
| 1 | drzwi | `zatrzasnął`, `drzwi`, `od` (-1, self, +1) |
| 2 | od | `drzwi`, `od`, `mieszkania` (-1, self, +1) |
| 3 | mieszkania | `od`, `mieszkania` (-1, self) |

No features for positions -2 because only edge 2+ has a -2 neighbor (edge 0).

**Code**: Same `concraft.schema/from-conf` machinery as the guesser, but with a different `SchemaConf` (the disambiguator's). The `low-orth-block` generates lowercased word forms; the `oov-only` flag on prefix/suffix/shape blocks means they produce no observations for known words.

### 4c. Tier decomposition

The disambiguator has two tiers. Each morphosyntactic tag is split into two independent atoms:

- **Tier 1** (`{pos, case, person}`): e.g., `praet:sg:m1:perf` -> `{pos=praet, case=_, person=_}` (praet has no case or person)
- **Tier 2** (`{number, gender, degree, aspect, ...}`): e.g., `praet:sg:m1:perf` -> `{number=sg, gender=m1, aspect=perf}`

The CRF operates on each tier as an independent layer, allowing it to learn separate transition patterns for e.g. case agreement vs. number agreement.

**Code**: `concraft.positional/split-tag` ([`src/concraft/positional.clj`](../src/concraft/positional.clj)) takes the tier definitions and a parsed positional tag, and for each tier, selects the relevant subset of attributes (filtering by `:with-atts`), optionally including POS (`:with-pos`) and EOS (`:with-eos`). The result is a vector of atoms, one per tier. During encoding in `concraft.crf.chain2/encode-sent`, each atom is looked up in the corresponding per-layer label codec.

### 4d. Marginal probabilities

The disambiguator CRF runs forward-backward on the two-tier structure and computes marginal probabilities. It also runs Viterbi (`fast-tag`) to identify the best global path for the "disamb" markers.

**Code**: `concraft.disamb/disamb-probs` ([`src/concraft/disamb.clj`](../src/concraft/disamb.clj)) calls `concraft.crf.chain2/marginals` with `log-sum-exp` as the accumulation function. The chain2 forward-backward is in `concraft.crf.chain2` ([`src/concraft/crf/chain2.clj`](../src/concraft/crf/chain2.clj)):
- The forward/backward tables are indexed by `(Pos, Pos)` pairs where `Pos` is `:beg`, `[:mid EdgeIx]`, or `:end` — the second-order state tracks both the current and previous label.
- `on-word` computes ψ for each `EdgeIx` by looking up observation features in the `OMap` (a compressed sparse structure with `o-beg`, `o-lb`, `o-ix` arrays).
- `on-transition` generates `TFeat1`, `TFeat2`, or `TFeat3` features depending on how many positions are known (1, 2, or 3), and looks them up in the corresponding `t1-map`, `t2-map`, `t3-map` arrays via index arithmetic.
- Memoization uses `atom`-backed caches keyed by `[u v]` pairs.
- `disamb-best` calls `fast-tag` (Viterbi) separately to determine which edges and labels lie on the globally optimal path; only those get the "disamb" marker. This matters for DAGs with segmentation ambiguity, where edges off the optimal path should not be marked.

### 4e. Disambiguator output

| Edge | Word | Top interpretation | Probability | Disamb? |
|------|------|--------------------|-------------|---------|
| 0 | Zatrzasnął | praet:sg:**m1**:perf | 0.9999 | yes |
| 1 | drzwi | subst:pl:**acc**:n:pt | 0.9849 | yes |
| 2 | od | prep:gen:nwok | 1.0000 | yes |
| 3 | mieszkania | subst:sg:**gen**:n:ncol | 1.0000 | yes |

The disambiguator resolves the key ambiguities:
- _Zatrzasnął_ is masculine personal (m1), not animate (m2) or inanimate (m3)
- _drzwi_ is accusative (direct object of _zatrzasnął_), not nominative, genitive, or vocative
- _od_ is the preposition (not the noun _oda_)
- _mieszkania_ is singular genitive (complement of _od_), not any of the plural forms or the gerund

## Step 5: Output formatting

The final output combines:
- The original word information (orth, lemma, tag) from the input
- The **marginal probability** from the disambiguator (formatted to 4 decimal places)
- The **eos flag** from the segmenter (step 3)
- The **disamb marker** from the Viterbi path (step 4)

Tags within each edge are sorted alphabetically by the Haskell `Ord` ordering of `Interp` (comparing base form, then tag text, then other fields).

**Code**: `concraft.polish/format-annotated-sents` ([`src/concraft/polish.clj`](../src/concraft/polish.clj)). It iterates over edges (in DAG edge-ID order), sorts each edge's tags using `concraft.format/compare-interp` ([`src/concraft/format.clj`](../src/concraft/format.clj)), and for each tag emits a tab-separated line. For OOV words, an implicit `ign` line is appended (matching the Haskell format printer). The lemma column uses the word's orth for OOV words (`if (:known word) base else orth`). The EOS flag comes from the per-edge `eos-per-edge` map computed during segmentation. A trailing blank line separates paragraphs.

```
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m1:perf   0.9999                disamb
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m2:perf   0.0001
0  1  Zatrzasnął  zatrzasnąć  praet:sg:m3:perf   0.0000
1  2  drzwi       drzwi       subst:pl:acc:n:pt   0.9849                disamb
1  2  drzwi       drzwi       subst:pl:gen:n:pt   0.0000
1  2  drzwi       drzwi       subst:pl:nom:n:pt   0.0151
1  2  drzwi       drzwi       subst:pl:voc:n:pt   0.0000
2  3  od          od          prep:gen:nwok        1.0000                disamb
2  3  od          oda         subst:pl:gen:f       0.0000
3  4  mieszkania  mieszkanie  subst:pl:acc:n:ncol  0.0000       eos
3  4  mieszkania  mieszkanie  subst:pl:nom:n:ncol  0.0000       eos
3  4  mieszkania  mieszkanie  subst:pl:voc:n:ncol  0.0000       eos
3  4  mieszkania  mieszkanie  subst:sg:gen:n:ncol  1.0000       eos     disamb
3  4  mieszkania  mieszkać    ger:pl:acc:n:imperf:aff  0.0000   eos
3  4  mieszkania  mieszkać    ger:pl:nom:n:imperf:aff  0.0000   eos
3  4  mieszkania  mieszkać    ger:sg:gen:n:imperf:aff  0.0000   eos
```

## Summary of the pipeline

```
Input DAG (16 lines, 4 edges, 16 interpretations)
  │
  ├─ Step 1: GUESSER (CRF chain1, 103K params)
  │   Feature extraction -> encode -> forward-backward -> marginals
  │   Assigns initial probabilities from word shape + context
  │   Code: guesser.clj -> schema.clj -> crf/chain1.clj
  │
  ├─ Step 2: ADD EOS MARKERS
  │   Each tag duplicated into eos=true/eos=false variants
  │   Code: polish.clj (add-eos-markers)
  │
  ├─ Step 3: SEGMENTER (CRF chain2, 280K params, 1 tier)
  │   Feature extraction -> encode -> Viterbi -> resolve EOS
  │   Marks "mieszkania" as end-of-sentence
  │   Code: disamb.clj -> crf/chain2.clj (fast-tag) -> polish.clj (resolve-eos, segment)
  │
  ├─ Step 4: DISAMBIGUATOR (CRF chain2, 6.4M params, 2 tiers)
  │   Strip EOS -> feature extraction -> encode -> marginals + Viterbi
  │   Selects best tag per word using full sentence context
  │   Code: disamb.clj -> crf/chain2.clj (marginals, fast-tag) -> positional.clj (split-tag)
  │
  └─ Step 5: FORMAT OUTPUT
      Combine probabilities + EOS flags + disamb markers
      Code: polish.clj (format-annotated-sents) -> format.clj (compare-interp)
      -> Output DAG (16 lines, same 4 edges, with probabilities)
```

Total processing time: ~60ms (after model loading).

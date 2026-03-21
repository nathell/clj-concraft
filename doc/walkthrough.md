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
node 0 —[edge 0: Zatrzasnął]→ node 1 —[edge 1: drzwi]→ node 2 —[edge 2: od]→ node 3 —[edge 3: mieszkania]→ node 4
```

## Step 0: Model loading

Before tagging, the model is loaded from a gzip-compressed binary file (~84 MB). It contains:

- **Tagset**: 40 parts of speech, 14 grammatical attributes with their possible values
- **Guesser** (CRF chain1): for predicting tags of unknown words — 103,702 parameters
- **Segmenter** (CRF chain2 tiers): for detecting sentence boundaries — 280,659 parameters, 1 tier (`{pos, eos}`)
- **Disambiguator** (CRF chain2 tiers): for selecting the best tag in context — 6,437,169 parameters, 2 tiers (`{pos, case, person}` and `{number, gender, degree, aspect, ...}`)

The binary format uses Haskell's `Data.Binary` serialization, with doubles encoded via the pre-0.8 `decodeFloat` representation (25 bytes per double instead of the usual 8-byte IEEE 754).

## Step 1: Guessing (CRF chain1)

**Purpose**: Assign prior probabilities to each interpretation, informed by word shape and context.

Even though all four words in this sentence are known (they appear in the morphological dictionary), the guesser still runs on them. For known words, the guesser's output becomes the initial weight for each tag.

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

### 1b. Sentence encoding

Each edge's observations are mapped to internal IDs via the observation codec. The label set for each edge is constrained to the morphological interpretations present in the input (e.g., edge 0 can only be `praet:sg:m1:perf`, `praet:sg:m2:perf`, or `praet:sg:m3:perf`).

### 1c. Forward-backward algorithm

The CRF chain1 is a first-order linear-chain conditional random field. It computes:

- **Observation potential** ψ(edge, label) — the product of feature weights for observation features matching this (edge, label) pair
- **Forward values** α(edge, label) — cumulative probability of reaching this edge with this label, considering all preceding edges
- **Backward values** β(edge, label) — cumulative probability of reaching the end from this edge with this label
- **Partition function** Z = Σ α(final, label) — normalization factor
- **Marginal probability** P(label | sentence) = α(edge, label) × β(edge, label) / Z

All arithmetic is in log-domain for numerical stability: multiplications become additions, and summations use the log-sum-exp trick.

### 1d. Guesser output

The marginal probabilities from the guesser:

| Edge | Word | Top interpretation | Probability |
|------|------|--------------------|-------------|
| 0 | Zatrzasnął | praet:sg:**m1**:perf | 0.9994 |
| 1 | drzwi | subst:pl:**acc**:n:pt | 0.9999 |
| 2 | od | prep:gen:nwok | 1.0000 |
| 3 | mieszkania | subst:sg:**gen**:n:ncol | 0.9919 |

These probabilities become the weights of the tags going forward. Note that the guesser already strongly favors the correct analysis based on word shape alone — suffixes like _-ął_ are very characteristic of `praet:sg:m1`.

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

### 3b. EOS resolution

The `resolve-eos` function examines the Viterbi output: if all selected interpretations on an edge have `eos=true`, the entire edge is marked as EOS. For edge 3, all four selected `subst` variants have `eos=true`, so the edge is resolved as EOS. All other edges are resolved as non-EOS.

### 3c. Sentence splitting

Since edge 3 is the final edge AND has `eos=true`, there's no split point (you only split when a non-final edge has EOS). The paragraph remains a single sentence.

## Step 4: Disambiguation (CRF chain2 tiers)

**Purpose**: Select the most likely interpretation for each word, considering sentence context.

### 4a. EOS stripping

Before running the disambiguator, the EOS variants are collapsed: `eos=true` and `eos=false` versions of the same tag are merged (keeping the higher weight). This restores the original tag count per edge.

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

### 4c. Tier decomposition

The disambiguator has two tiers. Each morphosyntactic tag is split into two independent atoms:

- **Tier 1** (`{pos, case, person}`): e.g., `praet:sg:m1:perf` → `{pos=praet, case=_, person=_}` (praet has no case or person)
- **Tier 2** (`{number, gender, degree, aspect, ...}`): e.g., `praet:sg:m1:perf` → `{number=sg, gender=m1, aspect=perf}`

The CRF operates on each tier as an independent layer, allowing it to learn separate transition patterns for e.g. case agreement vs. number agreement.

### 4d. Marginal probabilities

The disambiguator CRF runs forward-backward on the two-tier structure and computes marginal probabilities. It also runs Viterbi (`fast-tag`) to identify the best global path for the "disamb" markers.

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
  │   Feature extraction → encode → forward-backward → marginals
  │   Assigns initial probabilities from word shape + context
  │
  ├─ Step 2: ADD EOS MARKERS
  │   Each tag duplicated into eos=true/eos=false variants
  │
  ├─ Step 3: SEGMENTER (CRF chain2, 280K params, 1 tier)
  │   Feature extraction → encode → Viterbi → resolve EOS
  │   Marks "mieszkania" as end-of-sentence
  │
  ├─ Step 4: DISAMBIGUATOR (CRF chain2, 6.4M params, 2 tiers)
  │   Strip EOS → feature extraction → encode → marginals + Viterbi
  │   Selects best tag per word using full sentence context
  │
  └─ Step 5: FORMAT OUTPUT
      Combine probabilities + EOS flags + disamb markers
      → Output DAG (16 lines, same 4 edges, with probabilities)
```

Total processing time: ~60ms (after model loading).

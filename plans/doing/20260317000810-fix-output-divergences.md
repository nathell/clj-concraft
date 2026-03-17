# Fix Output Divergences

Match clj-concraft output to Haskell reference (byte-identical on small-input.dag).

## Current diff (14/16 lines match)

### Issue 1: Edge 1 (drzwi) — wrong disamb pick + probabilities
- **Expected**: `subst:pl:acc:n:pt 0.9849 disamb`, `subst:pl:nom:n:pt 0.0151`
- **Actual**:   `subst:pl:acc:n:pt 0.0269`, `subst:pl:nom:n:pt 0.9731 disamb`
- **Root cause hypothesis**: CRF chain2 forward-backward computes different marginals than Haskell. Likely a bug in the second-order transition potential lookup, the OMap observation lookup, or the memoized forward/backward recurrence. May also be caused by incorrect encoding of labels into Cb (complex labels) — the tier splitting or codec lookup may produce wrong internal IDs.
- **Debug approach**: Compare intermediate values (ψ, α, β) with Haskell at each step. Start by verifying that `encode-sent` for chain2 produces the same internal representation as Haskell.

### Issue 2: Edge 3 (mieszkania) — missing "eos" markers
- **Expected**: all interps on edge 3 have `eos` column = "eos"
- **Actual**: `eos` column is empty
- **Root cause hypothesis**: The segmenter CRF (chain2 with `{withPos=true, withEos=true}` tier) should resolve EOS. Our `resolve-eos` function or the segmenter invocation may not correctly propagate the EOS=true decision. Alternatively, the EOS marker addition (`add-eos-markers`) may not correctly double all tags with eos variants, or the segmenter codec may not find the eos-bearing atoms.
- **Debug approach**: Print the segmenter's disamb output (`seg-disambs`) to see which interps it selects. Check if the selected interps have `eos=true`. Also verify the segmenter's `encode-sent` correctly encodes the EOS tier.

### Issue 3: Trailing newline
- **Expected**: file ends with `\n\n` (blank line after last sentence)
- **Actual**: file ends with just `\n`
- **Fix**: append extra `\n` in `format-annotated-sents`

## Approach
1. Fix trailing newline (trivial)
2. Debug segmenter EOS resolution (add tracing to `anno-all`)
3. Debug chain2 marginals (compare encoded sentence with Haskell, then compare potentials)

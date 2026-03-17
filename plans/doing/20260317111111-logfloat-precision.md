# LogFloat Precision in CRF Chain2

## Problem

15/40 paragraphs in example-input.dag have small probability differences (4th-5th decimal place) compared to the Haskell reference. This occasionally flips which tag gets the "disamb" marker when two tags have nearly equal probabilities.

## Root cause

The Haskell code uses `Data.Number.LogFloat` which stores values as `log(x)` internally and provides arithmetic operations (`+`, `-`, `*`, `/`, `sum`) that are numerically stable in log-domain. Key operations:

- `L.sum :: [LogFloat] -> LogFloat` — log-sum-exp with careful handling of edge cases
- `(*)` on LogFloat — addition in log-domain (exact)
- `(/)` on LogFloat — subtraction in log-domain (exact)
- Comparison operators work on the internal log representation

Our Clojure code uses raw `double` log-domain values with a manual `log-sum-exp`. The precision differences likely come from:

1. **Accumulation order**: `log-sum-exp` over a `for` lazy seq vs Haskell's strict `L.sum` over a list — different summation order can change results by ULP
2. **Memoization cache hits**: The chain2 forward-backward uses recursive memoization; floating-point non-associativity means the order of cache population affects results
3. **The `(u-v)+w` trick in chain2**: The Haskell chain2 inference DOES still use this trick (unlike chain1 which we replaced). LogFloat handles the subtraction stably; our code may need a similar approach

## Approach

1. **Verify**: Dump chain2 α/β values from both Haskell and Clojure for a small failing paragraph, compare at each step to find where divergence starts
2. **Investigate LogFloat's `sum`**: Check if it uses a specific summation algorithm (e.g., Kahan, pairwise) vs our simple reduce
3. **Consider**: Implement a Clojure LogFloat type that mirrors Haskell's, or use `BigDecimal` for intermediate sums
4. **Alternative**: If precision is inherently limited by `double`, document the expected difference and consider it acceptable (tagging accuracy is unaffected — correct tag is always in top-2)

## Impact

Minor — affects output formatting only (which tag gets "disamb" when probabilities are tied at 4 decimal places). Does not affect tagging accuracy.

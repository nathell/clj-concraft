# clj-concraft

A pure Clojure reimplementation of [Concraft-pl][1], a morphosyntactic tagger for Polish based on constrained conditional random fields.

 [1]: https://github.com/kawu/concraft-pl

## LLM disclosure

The vast majority of code in this repository was written by an LLM (Claude Opus 4.6). The code was then lightly scrutinized and vetted by a human (Daniel Janus) to verify that it does what it says on the label; however, hallucinations are still possible.

Note that this is **not** a clean-room implementation: the LLM had access to the original source code. Thus, clj-concraft should be viewed as a LLM-facilitated translation, rather than an original implementation.

Use clj-concraft at your own risk. If you do so, you are strongly encouraged to do your own code review before use.

## Status

At the moment, clj-concraft only supports the tagging pipeline; it is unable to train models. It is, however, able to reuse models trained by the original Concraft-pl (it supports the same binary format).

The output of clj-concraft has been cross-validated with original Concraft-pl on the [example input][2], yielding a 100% match.

 [2]: https://zil.ipipan.waw.pl/Concraft?action=AttachFile&do=view&target=format-examples.zip

## Releases

There are none, but you can use clj-concraft as a Git dependency from within a `deps.edn`-based project. Add this to your `deps.edn`:

```clojure
clj-concraft/clj-concraft {:git/url "https://github.com/nathell/clj-concraft.git"
                           :git/sha "dae363bdc73473106f1158ef4bb673926c2c21e6"}
```

Replace the `:git/sha` with the most recent commit ID from this repo.

## Interoperability

Like the original, clj-concraft is coupled with [Morfeusz][3], the morphological analyzer for Polish. For ease of interoperation and to keep everything on the JVM, you may want to use it with [JMorfeusz][4] instead.

See the [szlauch][5] project for an example.

 [3]: https://morfeusz.sgjp.pl/
 [4]: https://github.com/nathell/jmorfeusz
 [5]: https://github.com/nathell/szlauch

## Authors

The person responsible for clj-concraft (the equivalent of “author” for human-written projects, overseeing LLMs and verifying their output) is [Daniel Janus][6].

 [6]: https://danieljanus.pl

clj-concraft is based on Concraft-pl and Concraft, which are written and copyright by Jakub Waszczuk.

## License

2-clause BSD, the same as Concraft-pl (see LICENSE.txt).

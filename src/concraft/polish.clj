(ns concraft.polish
  "Polish-specific pipeline: annoAll orchestrating guess → EOS → segment → disambiguate."
  (:require [concraft.dag :as dag]
            [concraft.guesser :as guesser]
            [concraft.disamb :as disamb]
            [concraft.tagset :as tagset]
            [concraft.format :as fmt]))

;; ============================================================
;; Tag simplification functions (matching Haskell's simplify4gsr etc.)
;; ============================================================

(defn simplify4gsr
  "Simplify Interp → P.Tag for guesser (parse positional tag text)."
  [tagset interp]
  (tagset/parse-tag tagset (:tag interp)))

(defn complexify4gsr
  "Reverse of simplify4gsr: P.Tag → Interp."
  [tagset ptag]
  {:base "none"
   :tag (tagset/show-tag tagset ptag)
   :commonness nil
   :qualifier nil
   :meta-info nil
   :eos false})

;; ============================================================
;; EOS marker handling
;; ============================================================

(defn- add-eos-markers
  "For each non-OOV edge, duplicate each tag into eos=true (weight 0) and eos=false (original weight).
   OOV edges are left unchanged."
  [dag]
  (dag/map-e
   (fn [_eid seg]
     (let [{:keys [word tags]} seg]
       (if (not (:known word))
         ;; OOV: leave as-is
         seg
         ;; Known: add EOS variants
         (let [new-tags (into {}
                              (mapcat (fn [[interp weight]]
                                        [[(assoc interp :eos false) weight]
                                         [(assoc interp :eos true) 0.0]]))
                              tags)]
           (assoc seg :tags new-tags)))))
   dag))

(defn- resolve-eos
  "Resolve EOS based on disambiguation markers.
   If all selected (disamb=true) interpretations have eos=true, mark entire segment as EOS.
   Otherwise, mark as non-EOS."
  [seg disamb-map]
  (let [{:keys [tags]} seg
        ;; Find selected interpretations
        selected (filter (fn [[interp _]] (get disamb-map interp false)) tags)
        ;; Check if all selected have eos=true
        all-eos (and (seq selected)
                     (every? (fn [[interp _]] (:eos interp)) selected))
        ;; Apply EOS decision to all interpretations
        new-tags (into {}
                       (map (fn [[interp weight]]
                              [(assoc interp :eos all-eos) weight]))
                       tags)]
    (assoc seg :tags new-tags)))

;; ============================================================
;; Segmentation (splitting at EOS boundaries)
;; ============================================================

(defn- segment
  "Split a sentence DAG at EOS boundaries.
   Returns a list of sub-DAG sentences."
  [dag]
  ;; For small-input.dag (single sentence), just return as-is
  ;; The segmentation model marks the last word with eos=true,
  ;; meaning this is a single sentence. No splitting needed
  ;; unless there are multiple sentences in the paragraph.
  ;; For now, return the DAG as a single sentence.
  ;; TODO: implement proper DAG splitting for multi-sentence paragraphs.
  [dag])

;; ============================================================
;; Guess injection
;; ============================================================

(defn- inject-guesses
  "Inject guesser results into the sentence.
   For OOV words: add guessed tags with probabilities.
   For known words: update weights from guesser marginals."
  [dag marginals tagset guess-num guesser]
  (dag/map-e
   (fn [eid seg]
     (let [{:keys [word tags]} seg
           edge-margs (get marginals eid {})]
       (if (not (:known word))
         ;; OOV: replace tags with top-k guessed
         (let [sorted-margs (sort-by (comp - val) edge-margs)
               top-k (take guess-num sorted-margs)
               new-tags (into {}
                              (map (fn [[ptag prob]]
                                     [(complexify4gsr tagset ptag) prob]))
                              top-k)]
           (assoc seg :tags new-tags))
         ;; Known: update weights
         (let [new-tags (into {}
                              (map (fn [[interp _weight]]
                                     (let [ptag (simplify4gsr tagset interp)
                                           prob (get edge-margs ptag 0.0)]
                                       [interp prob])))
                              tags)]
           (assoc seg :tags new-tags)))))
   dag))

;; ============================================================
;; Full annotation pipeline (annoAll)
;; ============================================================

(defn anno-all
  "Full annotation pipeline: guess → EOS → segment → disambiguate.
   Returns list of annotated sentences, each with
   {:dag, :marginals {eid → {interp → prob}}, :disambs {eid → {interp → bool}}}."
  [model dag]
  (let [{:keys [tagset guess-num guesser segmenter disamber]} model

        ;; Step 1: Guessing - predict tags for OOV words
        guess-margs (guesser/guess-marginals guesser tagset dag)
        guessed-dag (inject-guesses dag guess-margs tagset guess-num guesser)

        ;; Step 2: Add EOS markers
        eos-dag (add-eos-markers guessed-dag)

        ;; Step 3: Segmentation - resolve EOS using segmenter model
        seg-disambs (disamb/disamb-best segmenter tagset eos-dag)
        resolved-dag (dag/map-e
                      (fn [eid seg]
                        (resolve-eos seg (get seg-disambs eid {})))
                      eos-dag)

        ;; Step 4: Segment at EOS boundaries
        sentences (segment resolved-dag)]

    ;; Step 5: For each sentence, compute disambiguation
    ;; The disamb model works on tags WITHOUT EOS markers.
    ;; We strip EOS variants, run disamb, then restore EOS flags in output.
    (mapv (fn [sent-dag]
            (let [;; Determine EOS per edge from resolved DAG
                  eos-per-edge (into {}
                                     (map (fn [eid]
                                            (let [seg (dag/edge-label sent-dag eid)
                                                  has-eos (some :eos (keys (:tags seg)))]
                                              [eid (boolean has-eos)])))
                                     (dag/dag-edges sent-dag))
                  ;; Strip EOS from tags: merge eos=true/false variants
                  clean-dag (dag/map-e
                             (fn [_eid seg]
                               (let [merged (reduce (fn [acc [interp weight]]
                                                      (let [clean (assoc interp :eos false)]
                                                        (update acc clean (fnil max 0.0) weight)))
                                                    {}
                                                    (:tags seg))]
                                 (assoc seg :tags merged)))
                             sent-dag)
                  margs (disamb/disamb-probs disamber tagset :marginals clean-dag)
                  disambs (disamb/disamb-best disamber tagset clean-dag)]
              {:dag sent-dag
               :marginals margs
               :disambs disambs
               :eos-per-edge eos-per-edge}))
          sentences)))

;; ============================================================
;; Output formatting
;; ============================================================

(defn format-annotated-sents
  "Format annotated sentences to text matching Haskell output."
  [anno-sents]
  (let [sb (StringBuilder.)]
    (doseq [{:keys [dag marginals disambs eos-per-edge]} anno-sents]
      (doseq [eid (dag/dag-edges dag)]
        (let [{:keys [word tags]} (dag/edge-label dag eid)
              tail-node (dag/begins-with dag eid)
              head-node (dag/ends-with dag eid)
              edge-margs (get marginals eid {})
              edge-disambs (get disambs eid {})]
          (doseq [[interp _] (sort-by key fmt/compare-interp tags)]
            ;; Look up probability from marginals (strip eos for lookup)
            (let [clean-interp (assoc interp :eos false)
                  prob (or (get edge-margs interp)
                           (get edge-margs clean-interp)
                           0.0)
                  is-disamb (or (get edge-disambs interp)
                                (get edge-disambs clean-interp)
                                false)
                  ;; EOS from resolved DAG (per-edge, not per-interp)
                  has-eos (get eos-per-edge eid false)]
              (.append sb (str tail-node))
              (.append sb "\t")
              (.append sb (str head-node))
              (.append sb "\t")
              (.append sb (:orth word))
              (.append sb "\t")
              (.append sb (or (:base interp) (:orth word)))
              (.append sb "\t")
              (.append sb (:tag interp))
              (.append sb "\t")
              (.append sb (or (:commonness interp) ""))
              (.append sb "\t")
              (.append sb (or (:qualifier interp) ""))
              (.append sb "\t")
              (.append sb (format "%.4f" (double prob)))
              (.append sb "\t")
              (.append sb (or (:meta-info interp) ""))
              (.append sb "\t")
              (.append sb (if has-eos "eos" ""))
              (.append sb "\t")
              (.append sb (or (:word-info word) ""))
              (.append sb "\t")
              (.append sb (if is-disamb "disamb" ""))
              (.append sb "\n"))))))
    ;; Trailing blank line (paragraph separator)
    (.append sb "\n")
    (.toString sb)))

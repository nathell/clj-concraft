(ns concraft.disamb
  "Disambiguation model: uses CRF chain2 tiers for segmentation and disambiguation."
  (:require [concraft.crf.chain2 :as crf2]
            [concraft.schema :as schema]
            [concraft.positional :as pos]
            [concraft.tagset :as tagset]
            [concraft.dag :as dag]))

(defn- simplify-tag-for-disamb
  "Simplify a Polish Interp tag to {posiTag, hasEos} for disamb."
  [tagset interp]
  {:posi-tag (tagset/parse-tag tagset (:tag interp))
   :has-eos (:eos interp)})

(defn- split-for-disamb
  "Split a simplified tag into atoms per tier.
   Returns vector of atoms, one per tier."
  [tiers {:keys [posi-tag has-eos]}]
  (pos/split-tag tiers posi-tag has-eos))

(defn disamb-probs
  "Compute disambiguation probabilities for a sentence.
   prob-type is :marginals or :max-probs.
   Returns {edge-id → {interp → probability}}."
  [disamb tagset prob-type dag]
  (let [{:keys [tiers schema-conf crf]} disamb
        schema-fn (schema/from-conf schema-conf)
        model (:model crf)
        label-codecs (:label-codecs crf)
        ;; Simplify and split functions
        simplify (fn [interp] (simplify-tag-for-disamb tagset interp))
        split (fn [simplified] (split-for-disamb tiers simplified))
        ;; Encode sentence
        encoded-dag (crf2/encode-sent crf schema-fn dag simplify split)
        ;; Choose accumulation function
        acc-fn (case prob-type
                 :marginals crf2/log-sum-exp
                 :max-probs (fn [xs] (if (empty? xs) crf2/neg-inf (reduce max xs))))
        ;; Run inference
        margs (crf2/marginals model encoded-dag acc-fn)]
    ;; Map CRF marginals back to original interps
    (into {}
          (map (fn [eid]
                 (let [seg (dag/edge-label dag eid)
                       edge-margs (get margs eid [])
                       ;; Build map from cb-ix to probability
                       cb-probs (into {} edge-margs)
                       ;; For each original interp, look up its cb-ix
                       result (into {}
                                    (map (fn [[interp _weight]]
                                           (let [simplified (simplify interp)
                                                 atoms (split simplified)
                                                 ;; Find matching cb-ix in encoded edge
                                                 enc (dag/edge-label encoded-dag eid)
                                                 int-atoms (vec (map-indexed
                                                                  (fn [k atom]
                                                                    (get (:to (nth label-codecs k)) atom))
                                                                  atoms))
                                                 cb-ix (first (keep-indexed
                                                                (fn [i cb]
                                                                  (when (= cb int-atoms) i))
                                                                (:lbs enc)))
                                                 prob (if cb-ix
                                                        (get cb-probs cb-ix 0.0)
                                                        0.0)]
                                             [interp prob])))
                                    (:tags seg))]
                   [eid result])))
          (dag/dag-edges dag))))

(defn disamb-best
  "Find the best tag for each edge using Viterbi (global optimal path).
   Returns {edge-id → {interp → Bool}} where True = on optimal path.
   Edges NOT on the optimal DAG path get all-False.
   Multiple interps that map to the same CRF atoms all get True if any does."
  [disamb tagset dag]
  (let [{:keys [tiers schema-conf crf]} disamb
        schema-fn (schema/from-conf schema-conf)
        model (:model crf)
        label-codecs (:label-codecs crf)
        simplify (fn [interp] (simplify-tag-for-disamb tagset interp))
        split (fn [simplified] (split-for-disamb tiers simplified))
        ;; Encode sentence
        encoded-dag (crf2/encode-sent crf schema-fn dag simplify split)
        ;; Run Viterbi to find globally optimal path
        viterbi-result (crf2/fast-tag model encoded-dag)]
    ;; Map back: for each edge, check if it's on the optimal path
    ;; and which interp matches the Viterbi-chosen label
    (into {}
          (map (fn [eid]
                 (let [seg (dag/edge-label dag eid)
                       chosen-cbix (get viterbi-result eid)  ;; nil if not on optimal path
                       enc (dag/edge-label encoded-dag eid)]
                   [eid (if (nil? chosen-cbix)
                          ;; Edge not on optimal path: all False
                          (into {} (map (fn [[interp _]] [interp false]) (:tags seg)))
                          ;; Edge on optimal path: match interps to chosen Cb
                          (let [chosen-cb (nth (:lbs enc) chosen-cbix)
                                ;; Map each interp to its encoded Cb
                                interp-results
                                (into {}
                                      (map (fn [[interp _]]
                                             (let [atoms (split (simplify interp))
                                                   int-atoms (vec (map-indexed
                                                                    (fn [k atom]
                                                                      (get (:to (nth label-codecs k)) atom))
                                                                    atoms))]
                                               [interp (= int-atoms chosen-cb)])))
                                      (:tags seg))]
                            interp-results))])))
          (dag/dag-edges dag))))

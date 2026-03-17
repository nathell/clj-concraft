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
  "Find the best tag for each edge (Viterbi-like via marginals argmax).
   Returns {edge-id → {interp → Bool}} where True = on optimal path.
   Multiple interps that map to the same CRF atoms all get True if any does."
  [disamb tagset dag]
  (let [{:keys [tiers]} disamb
        probs (disamb-probs disamb tagset :marginals dag)
        simplify (fn [interp] (simplify-tag-for-disamb tagset interp))
        split (fn [simplified] (split-for-disamb tiers simplified))]
    (into {}
          (map (fn [[eid interp-probs]]
                 (if (empty? interp-probs)
                   [eid {}]
                   ;; Find the best atoms (CRF-level comparison)
                   (let [best-interp (key (apply max-key val interp-probs))
                         best-atoms (split (simplify best-interp))]
                     [eid (into {}
                                (map (fn [[interp _]]
                                       (let [atoms (split (simplify interp))]
                                         [interp (= atoms best-atoms)])))
                                interp-probs)]))))
          probs)))

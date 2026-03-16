(ns concraft.guesser
  "Guesser: predict tags for OOV words using CRF chain1 marginals."
  (:require [concraft.crf.chain1 :as crf1]
            [concraft.schema :as schema]
            [concraft.dag :as dag]
            [concraft.tagset :as tagset]))

(defn guess-marginals
  "Run the guesser CRF on a sentence DAG to compute marginal probabilities.
   Returns a map {edge-id → {external-tag → probability}} for each edge."
  [guesser tagset dag]
  (let [{:keys [schema-conf crf]} guesser
        schema-fn (schema/from-conf schema-conf)
        model (:model crf)
        label-codec-from (:from (:label-codec crf))
        ;; Simplify: Interp → P.Tag (for codec lookup)
        simplify-tag (fn [interp]
                       (tagset/parse-tag tagset (:tag interp)))
        ;; Encode the sentence
        encoded-dag (crf1/encode-sent crf schema-fn dag simplify-tag)
        ;; Run forward-backward
        margs (crf1/marginals model encoded-dag)]
    ;; Decode: map internal label IDs back to external tags
    (into {}
          (map (fn [eid]
                 (let [edge-margs (get margs eid [])
                       decoded (into {}
                                     (keep (fn [[lb prob]]
                                             (when-let [maybe-tag (get label-codec-from lb)]
                                               (when maybe-tag
                                                 [maybe-tag prob]))))
                                     edge-margs)]
                   [eid decoded])))
          (dag/dag-edges dag))))

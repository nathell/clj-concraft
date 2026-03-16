(ns concraft.model
  "Top-level model loading for DAGSeg variant (version dagseg:0.11)."
  (:require [concraft.binary :as b]
            [concraft.tagset :as tagset]
            [concraft.schema :as schema]
            [concraft.positional :as pos]
            [concraft.crf.chain1 :as chain1]
            [concraft.crf.chain2 :as chain2]))

;; -- Polish-specific Interp type reader --
;; Interp Text has Binary: put base >> put tag >> put commonness >> put qualifier >> put metaInfo >> put eos

(defn- read-interp
  "Read a Polish Interp Text = {base, tag, commonness, qualifier, metaInfo, eos}."
  [dis]
  {:base (b/read-text dis)
   :tag (b/read-text dis)
   :commonness (b/read-maybe dis b/read-text)
   :qualifier (b/read-maybe dis b/read-text)
   :meta-info (b/read-maybe dis b/read-text)
   :eos (b/read-bool dis)})

;; -- Guesser --

(defn- read-guesser
  "Read Guesser: SchemaConf + CRF Ob P.Tag + zeroProbLabel + unkTagSet."
  [dis]
  (let [schema-conf (schema/read-schema-conf dis)
        crf (chain1/read-crf dis tagset/read-tag)
        zero-prob-label (tagset/read-tag dis)
        unk-tag-set (b/read-set dis read-interp)]
    {:schema-conf schema-conf
     :crf crf
     :zero-prob-label zero-prob-label
     :unk-tag-set unk-tag-set}))

;; -- Disamb --

(defn- read-disamb
  "Read Disamb: [Tier] + SchemaConf + CRF Ob Atom."
  [dis]
  (let [tiers (b/read-list dis pos/read-tier)
        schema-conf (schema/read-schema-conf dis)
        crf (chain2/read-crf dis pos/read-atom)]
    {:tiers tiers
     :schema-conf schema-conf
     :crf crf}))

;; -- Full model --

(defn load-model
  "Load a complete DAGSeg model from a gzip-compressed binary file.
   Returns {:tagset, :guess-num, :guesser, :segmenter, :disamber}."
  [path]
  (let [dis (b/open-gzip-binary path)
        version (b/read-string-haskell dis)
        _ (when-not (= version "dagseg:0.11")
            (throw (ex-info "Unsupported model version" {:version version})))
        tagset (tagset/read-tagset dis)
        guess-num (b/read-int64 dis)
        guesser (read-guesser dis)
        segmenter (read-disamb dis)
        disamber (read-disamb dis)]
    (.close dis)
    {:tagset tagset
     :guess-num guess-num
     :guesser guesser
     :segmenter segmenter
     :disamber disamber}))

(defn model-stats
  "Print structural statistics for a loaded model."
  [{:keys [tagset guess-num guesser segmenter disamber]}]
  (println "=== Model Statistics ===")
  (println "Tagset:" (count (:domains tagset)) "attrs," (count (:rules tagset)) "POS")
  (println "guessNum:" guess-num)
  (println)
  (println "--- Guesser ---")
  (println "  Schema:" (:schema-conf guesser))
  (let [crf (:crf guesser)]
    (println "  Ob codec:" (count (:to (:ob-codec crf))) "observations")
    (println "  Label codec:" (count (:to (:label-codec crf))) "labels")
    (println "  Model values:" (alength ^doubles (:values (:model crf))) "parameters")
    (println "  r0 size:" (alength ^longs (:r0 (:model crf))) "default labels")
    (println "  Zero-prob label:" (:zero-prob-label guesser))
    (println "  Unknown tag set:" (count (:unk-tag-set guesser)) "tags"))
  (println)
  (println "--- Segmenter ---")
  (println "  Tiers:" (:tiers segmenter))
  (println "  Schema:" (:schema-conf segmenter))
  (let [crf (:crf segmenter)]
    (println "  Layers:" (:num-layers crf))
    (println "  Ob codec:" (count (:to (:ob-codec crf))) "observations")
    (println "  Label codecs:" (mapv #(count (:to %)) (:label-codecs crf)))
    (println "  Model values:" (alength ^doubles (:values (:model crf))) "parameters"))
  (println)
  (println "--- Disambiguator ---")
  (println "  Tiers:" (:tiers disamber))
  (println "  Schema:" (:schema-conf disamber))
  (let [crf (:crf disamber)]
    (println "  Layers:" (:num-layers crf))
    (println "  Ob codec:" (count (:to (:ob-codec crf))) "observations")
    (println "  Label codecs:" (mapv #(count (:to %)) (:label-codecs crf)))
    (println "  Model values:" (alength ^doubles (:values (:model crf))) "parameters")))

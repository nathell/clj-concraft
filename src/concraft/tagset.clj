(ns concraft.tagset
  "Positional tagset reader and tag manipulation.
   Haskell types: Tagset, Tag, POS = Text, Attr = Text, AttrVal = Text."
  (:require [concraft.binary :as b]
            [clojure.string :as str]))

;; -- Binary readers --

(defn read-tagset
  "Read a Haskell Tagset from binary stream.
   Tagset = {domains :: Map Attr (Set AttrVal), rules :: Map POS [(Attr, Optional)]}"
  [dis]
  (let [domains (b/read-map dis b/read-text
                  (fn [dis] (b/read-set dis b/read-text)))
        rules (b/read-map dis b/read-text
                (fn [dis] (b/read-list dis
                            (fn [dis] (b/read-pair dis b/read-text b/read-bool)))))]
    {:domains domains
     :rules rules}))

(defn read-tag
  "Read a Haskell Tag = {pos :: POS, atts :: Map Attr AttrVal}."
  [dis]
  (let [pos (b/read-text dis)
        atts (b/read-map dis b/read-text b/read-text)]
    {:pos pos :atts atts}))

;; -- Tag parsing/showing --

(defn parse-tag
  "Parse a colon-separated text tag into structured form using tagset.
   E.g., \"praet:sg:m1:perf\" → {:pos \"praet\" :atts {\"nmb\" \"sg\" \"gnd\" \"m1\" \"asp\" \"perf\"}}"
  [{:keys [rules domains]} tag-text]
  (let [parts (str/split tag-text #":")
        pos (first parts)
        vals (rest parts)
        rule (get rules pos)]
    (if (nil? rule)
      ;; Unknown POS (e.g., "ign") — return tag with just POS, no attributes
      {:pos pos :atts (sorted-map)}
    (let [atts (loop [rule-rest rule
                      vals-rest vals
                      acc (sorted-map)]
                 (if (empty? rule-rest)
                   acc
                   (let [[attr _optional] (first rule-rest)]
                     (if (empty? vals-rest)
                       acc
                       (recur (rest rule-rest)
                              (rest vals-rest)
                              (assoc acc attr (first vals-rest)))))))]
      {:pos pos :atts atts}))))

(defn show-tag
  "Convert a structured tag back to colon-separated text.
   E.g., {:pos \"praet\" :atts {\"nmb\" \"sg\" ...}} → \"praet:sg:m1:perf\""
  [{:keys [rules]} {:keys [pos atts]}]
  (let [rule (get rules pos)
        parts (into [pos]
                    (for [[attr _optional] rule
                          :let [val (get atts attr)]
                          :when val]
                      val))]
    (str/join ":" parts)))

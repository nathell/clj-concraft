(ns concraft.positional
  "Tier and Atom types for positional tag decomposition."
  (:require [concraft.binary :as b]))

(defn read-tier
  "Read a Tier = {withPos :: Bool, withEos :: Bool, withAtts :: Set Attr}."
  [dis]
  {:with-pos (b/read-bool dis)
   :with-eos (b/read-bool dis)
   :with-atts (b/read-set dis b/read-text)})

(defn read-atom
  "Read an Atom = {pos :: Maybe POS, atts :: Map Attr Text, eos :: Maybe Bool}."
  [dis]
  {:pos (b/read-maybe dis b/read-text)
   :atts (b/read-map dis b/read-text b/read-text)
   :eos (b/read-maybe dis b/read-bool)})

(defn split-tag
  "Split a positional tag into atoms per tier.
   tag = {:pos POS, :atts {Attr AttrVal}}
   Returns vector of atoms, one per tier."
  [tiers tag has-eos]
  (mapv (fn [{:keys [with-pos with-eos with-atts]}]
          {:pos (when with-pos (:pos tag))
           :atts (into (sorted-map)
                       (filter (fn [[k _]] (contains? with-atts k)))
                       (:atts tag))
           :eos (when with-eos has-eos)})
        tiers))

(ns concraft.schema
  "Feature extraction schema configuration and observation extraction."
  (:require [concraft.binary :as b]))

;; -- Binary readers for SchemaConf --

(defn- read-entry-body
  "Read a Body a = {range :: [Int], oovOnly :: Bool, args :: a}."
  [dis read-args]
  {:range (b/read-list dis b/read-int64)
   :oov-only (b/read-bool dis)
   :args (read-args dis)})

(defn- read-entry
  "Read an Entry a = Maybe (Body a)."
  [dis read-args]
  (b/read-maybe dis (fn [dis] (read-entry-body dis read-args))))

(defn- read-unit [_dis] nil)

(defn- read-int-list [dis] (b/read-list dis b/read-int64))

(defn read-schema-conf
  "Read SchemaConf: 8 optional entry blocks."
  [dis]
  {:orth-c         (read-entry dis read-unit)
   :low-orth-c     (read-entry dis read-unit)
   :low-prefixes-c (read-entry dis read-int-list)
   :low-suffixes-c (read-entry dis read-int-list)
   :known-c        (read-entry dis read-unit)
   :shape-c        (read-entry dis read-unit)
   :packed-c       (read-entry dis read-unit)
   :beg-packed-c   (read-entry dis read-unit)})

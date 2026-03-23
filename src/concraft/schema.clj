(ns concraft.schema
  "Feature extraction schema configuration and observation extraction.
   Reimplements NLP.Concraft.DAG.Schema and Control.Monad.Ox."
  (:require [concraft.binary :as b]
            [concraft.dag :as dag]
            [clojure.string :as str]))

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

;; ============================================================
;; Ox monad reimplementation
;; The Ox monad tracks a counter (starting at [1]) and accumulates
;; observations as [([Int], Text)] pairs.
;; Each `save` call (even with nil) increments the counter.
;; ============================================================

(def ox-tx
  (comp (map-indexed vector)
        (filter second)
        (map (fn [[i x]] [[(inc i)] x]))))

(defn- ox-exec
  "Execute a sequence of observation values, returning [([Int], Text)] pairs.
   values is a seq of (Maybe Text) — nil for missing observations."
  [values]
  (into [] ox-tx values))

;; ============================================================
;; Text observation helpers (from monad-ox Text module)
;; ============================================================

(defn- text-prefix
  "Prefix of length k, or nil if word is shorter."
  [k ^String text]
  (let [n (.codePointCount text 0 (.length text))]
    (cond
      (and (pos? k) (<= k n))
      (let [end (.offsetByCodePoints text 0 k)]
        (.substring text 0 end))

      (and (<= k 0) (pos? (+ n k)))
      (let [end (.offsetByCodePoints text 0 (+ n k))]
        (.substring text 0 end))

      :else nil)))

(defn- text-suffix
  "Suffix of length k, or nil if word is shorter."
  [k ^String text]
  (let [n (.codePointCount text 0 (.length text))]
    (cond
      (and (pos? k) (<= k n))
      (let [start (.offsetByCodePoints text 0 (- n k))]
        (.substring text start))

      (and (<= k 0) (pos? (+ n k)))
      (let [start (.offsetByCodePoints text 0 (- n (+ n k)))]
        (.substring text start))

      :else nil)))

(defn- text-shape
  "Shape: lower→l, upper→u, digit→d, other→x."
  [^String text]
  (let [sb (StringBuilder. (.length text))
        len (.length text)]
    (loop [i 0]
      (if (>= i len)
        (.toString sb)
        (let [cp (.codePointAt text i)
              c (cond
                  (Character/isLowerCase cp) \l
                  (Character/isUpperCase cp) \u
                  (Character/isDigit cp) \d
                  :else \x)]
          (.append sb c)
          (recur (+ i (Character/charCount cp))))))))

(defn- text-pack
  "Pack: remove adjacent duplicate characters."
  [^String text]
  (if (empty? text)
    ""
    (let [sb (StringBuilder.)]
      (.append sb (.charAt text 0))
      (dotimes [i (dec (.length text))]
        (let [c (.charAt text (inc i))]
          (when-not (= c (.charAt text i))
            (.append sb c))))
      (.toString sb))))

;; ============================================================
;; Shift function for DAG navigation
;; ============================================================

(defn- shift
  "Navigate k edges forward (positive) or backward (negative) in the DAG.
   Returns the target EdgeID or nil if boundary reached."
  [k edge-id dag]
  (cond
    (pos? k)
    (let [nexts (dag/next-edges dag edge-id)]
      (when (seq nexts)
        (recur (dec k) (first nexts) dag)))

    (neg? k)
    (let [prevs (dag/prev-edges dag edge-id)]
      (when (seq prevs)
        (recur (inc k) (last prevs) dag)))

    :else edge-id))

;; ============================================================
;; Schema block functions
;; Each block takes the sentence DAG and a list of absolute EdgeIDs,
;; and returns a seq of (Maybe Text) observation values.
;; The seq order must match the Ox counter assignment.
;; ============================================================

(defn- get-word
  "Get the word at an edge."
  [dag edge-id]
  (:word (dag/edge-label dag edge-id)))

(defn- get-orth
  "Get the orthographic form at an edge."
  [dag edge-id]
  (:orth (get-word dag edge-id)))

(defn- get-low-orth
  "Get the lowercased orthographic form at an edge."
  [dag edge-id]
  (str/lower-case (get-orth dag edge-id)))

(defn- is-oov?
  "Is the word at this edge out-of-vocabulary?"
  [dag edge-id]
  (not (:known (get-word dag edge-id))))

;; -- Block implementations --

(defn- orth-block [dag ks]
  (map #(get-orth dag %) ks))

(defn- low-orth-block [dag ks]
  (map #(get-low-orth dag %) ks))

(defn- low-prefixes-block [lengths dag ks]
  (for [k ks, n lengths]
    (text-prefix n (get-low-orth dag k))))

(defn- low-suffixes-block [lengths dag ks]
  (for [k ks, n lengths]
    (text-suffix n (get-low-orth dag k))))

(defn- known-block [dag ks]
  (map #(if (is-oov? dag %) "F" "T") ks))

(defn- shape-block [dag ks]
  (map #(text-shape (get-orth dag %)) ks))

(defn- packed-block [dag ks]
  (map #(text-pack (text-shape (get-orth dag %))) ks))

(defn- beg-packed-block [dag ks]
  (map (fn [k]
         (let [beg (if (= k 0) "T" "F")
               packed (text-pack (text-shape (get-orth dag k)))]
           (str beg "-" packed)))
       ks))

;; ============================================================
;; Schema construction from SchemaConf
;; ============================================================

(defn- apply-block
  "Apply a block function for a given entry config.
   Returns observations for the given edge-id, or empty if entry is nil."
  [block-fn entry dag edge-id]
  (when entry
    (let [{:keys [range oov-only]} entry
          ;; Resolve shifted positions, filter by oov if needed
          ks (for [offset range
                   :let [target (shift offset edge-id dag)]
                   :when target
                   :when (or (not oov-only) (is-oov? dag target))]
               target)]
      (block-fn dag ks))))

(defn from-conf
  "Build a schema function from SchemaConf.
   Returns a function (dag, edge-id) → [([Int], Text)] observations."
  [{:keys [orth-c low-orth-c low-prefixes-c low-suffixes-c
           known-c shape-c packed-c beg-packed-c]}]
  (fn [dag edge-id]
    (let [;; Collect all observation values from all blocks in order
          all-values (concat
                       (apply-block orth-block orth-c dag edge-id)
                       (apply-block low-orth-block low-orth-c dag edge-id)
                       (apply-block (partial low-prefixes-block (:args low-prefixes-c))
                                    low-prefixes-c dag edge-id)
                       (apply-block (partial low-suffixes-block (:args low-suffixes-c))
                                    low-suffixes-c dag edge-id)
                       (apply-block known-block known-c dag edge-id)
                       (apply-block shape-block shape-c dag edge-id)
                       (apply-block packed-block packed-c dag edge-id)
                       (apply-block beg-packed-block beg-packed-c dag edge-id))]
      (ox-exec all-values))))

(defn schematize
  "Apply a schema to a sentence DAG, producing observations per edge.
   Returns a map {EdgeID → [([Int], Text)]}."
  [schema dag]
  (into {}
        (map (fn [eid] [eid (schema dag eid)]))
        (dag/dag-edges dag)))

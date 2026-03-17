(ns concraft.format
  "DAG text format parser and printer.
   Tab-separated, 11-12 columns per line, blank-line sentence delimiters.

   Columns (0-indexed):
   0: tail-node  1: head-node  2: orth  3: lemma  4: tag
   5: commonness  6: qualifier  7: probability
   8: meta-info  9: eos  10: word-info  11: disamb (output only)"
  (:require [concraft.dag :as dag]
            [clojure.string :as str]))

;; -- Parsing --

(defn- parse-row
  "Parse a single tab-separated line into a row map."
  [line]
  (let [cols (str/split line #"\t" -1)]
    (when (>= (count cols) 8)
      (let [not-empty (fn [s] (when-not (str/blank? s) s))]
        {:tail-node (parse-long (nth cols 0))
         :head-node (parse-long (nth cols 1))
         :orth (nth cols 2)
         :lemma (nth cols 3)
         :tag (nth cols 4)
         :commonness (not-empty (nth cols 5 ""))
         :qualifier (not-empty (nth cols 6 ""))
         :prob (parse-double (nth cols 7 "0"))
         :meta-info (not-empty (nth cols 8 ""))
         :eos (= "eos" (nth cols 9 ""))
         :word-info (not-empty (nth cols 10 ""))
         :disamb (= "disamb" (nth cols 11 ""))}))))

(defn- rows->dag
  "Convert parsed rows into a DAG sentence.
   Groups rows by (tail-node, head-node) pairs to form edges.
   Each edge label is a Seg = {:word {:orth, :known, :word-info}, :tags WMap}
   where WMap = {Interp → Double}."
  [rows]
  (let [;; Group by (tail, head) preserving insertion order
        ;; Use a LinkedHashMap to avoid array-map's 8-entry limit
        lhm (java.util.LinkedHashMap.)
        _ (doseq [row rows]
            (let [k [(:tail-node row) (:head-node row)]]
              (.put lhm k (conj (or (.get lhm k) []) row))))
        groups (into [] lhm)
        ;; Build edges from groups
        edges (mapv (fn [[[tail head] rows]]
                      (let [first-row (first rows)
                            known (not (some #(= "ign" (:tag %)) rows))
                            tags (into {}
                                       (map (fn [row]
                                              [{:base (:lemma row)
                                                :tag (:tag row)
                                                :commonness (:commonness row)
                                                :qualifier (:qualifier row)
                                                :meta-info (:meta-info row)
                                                :eos (:eos row)}
                                               (:prob row)]))
                                       rows)]
                        {:tail tail
                         :head head
                         :label {:word {:orth (:orth first-row)
                                        :known known
                                        :word-info (:word-info first-row)}
                                 :tags tags}}))
                    groups)]
    (dag/from-edges edges)))

(defn parse-sent
  "Parse a paragraph (string with no blank lines) into a DAG sentence."
  [text]
  (let [lines (str/split-lines text)
        rows (keep parse-row lines)]
    (rows->dag rows)))

(defn parse-data
  "Parse input text into a list of DAG sentences (paragraphs).
   Paragraphs are separated by blank lines."
  [text]
  (let [paragraphs (str/split text #"\n\n+")]
    (mapv parse-sent (remove str/blank? paragraphs))))

;; -- Interp ordering (matches Haskell Ord for Interp Text) --

(defn- compare-maybe
  "Compare two Maybe values: Nothing < Just x."
  [a b]
  (cond
    (and (nil? a) (nil? b)) 0
    (nil? a) -1
    (nil? b) 1
    :else (compare a b)))

(defn compare-interp
  "Compare two Interp maps in Haskell Ord order:
   base, tag, commonness, qualifier, meta-info, eos."
  [a b]
  (let [c (compare (:base a) (:base b))]
    (if-not (zero? c) c
      (let [c (compare (:tag a) (:tag b))]
        (if-not (zero? c) c
          (let [c (compare-maybe (:commonness a) (:commonness b))]
            (if-not (zero? c) c
              (let [c (compare-maybe (:qualifier a) (:qualifier b))]
                (if-not (zero? c) c
                  (let [c (compare-maybe (:meta-info a) (:meta-info b))]
                    (if-not (zero? c) c
                      (compare (:eos a) (:eos b)))))))))))))

;; -- Printing --

(defn- format-prob
  "Format a probability to 4 decimal places."
  [^double p]
  (format "%.4f" p))

(defn show-sent
  "Format an annotated sentence DAG back to tab-separated text.
   anno = {:guessSent dag, :disambs anno-dag, :marginals anno-dag}
   For now, takes the DAG with probabilities and disamb annotations."
  [{:keys [dag disambs]}]
  (let [sb (StringBuilder.)]
    (doseq [eid (dag/dag-edges dag)]
      (let [{:keys [word tags]} (dag/edge-label dag eid)
            tail-node (dag/begins-with dag eid)
            head-node (dag/ends-with dag eid)
            disamb-map (when disambs (dag/edge-label disambs eid))]
        (doseq [[interp prob] (sort-by key compare-interp tags)]
          (let [{:keys [base tag commonness qualifier meta-info eos]} interp
                is-disamb (when disamb-map (get disamb-map interp false))]
            (.append sb (str tail-node))
            (.append sb "\t")
            (.append sb (str head-node))
            (.append sb "\t")
            (.append sb (:orth word))
            (.append sb "\t")
            (.append sb (or base (:orth word)))
            (.append sb "\t")
            (.append sb tag)
            (.append sb "\t")
            (.append sb (or commonness ""))
            (.append sb "\t")
            (.append sb (or qualifier ""))
            (.append sb "\t")
            (.append sb (format-prob prob))
            (.append sb "\t")
            (.append sb (or meta-info ""))
            (.append sb "\t")
            (.append sb (if eos "eos" ""))
            (.append sb "\t")
            (.append sb (or (:word-info word) ""))
            (.append sb "\t")
            (.append sb (if is-disamb "disamb" ""))
            (.append sb "\n")))))
    (.toString sb)))

(defn show-data
  "Format a list of annotated sentences to text.
   Sentences separated by blank lines."
  [sents]
  (str/join "\n" (map show-sent sents)))

;; -- Simple round-trip (input only, no annotations) --

(defn show-input-sent
  "Format an input sentence DAG back to tab-separated text (no annotations)."
  [dag]
  (let [sb (StringBuilder.)]
    (doseq [eid (dag/dag-edges dag)]
      (let [{:keys [word tags]} (dag/edge-label dag eid)
            tail-node (dag/begins-with dag eid)
            head-node (dag/ends-with dag eid)]
        (doseq [[interp prob] tags]
          (let [{:keys [base tag commonness qualifier meta-info eos]} interp]
            (.append sb (str tail-node))
            (.append sb "\t")
            (.append sb (str head-node))
            (.append sb "\t")
            (.append sb (:orth word))
            (.append sb "\t")
            (.append sb (or base (:orth word)))
            (.append sb "\t")
            (.append sb tag)
            (.append sb "\t")
            (.append sb (or commonness ""))
            (.append sb "\t")
            (.append sb (or qualifier ""))
            (.append sb "\t")
            (.append sb (format-prob prob))
            (.append sb "\t")
            (.append sb (or meta-info ""))
            (.append sb "\t")
            (.append sb (if eos "eos" ""))
            (.append sb "\t")
            (.append sb (or (:word-info word) ""))
            (.append sb "\n")))))
    (.toString sb)))

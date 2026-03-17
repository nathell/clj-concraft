(ns concraft.crf.chain1
  "CRF chain1 constrained model reader and inference.

   Binary format:
   CRF = codec + model
   Codec = (AtomCodec Ob, AtomCodec (Maybe s))
   AtomCodec a = (Map a Int, IntMap a)
   Model = values(UVec Double) + ixMap(Map Feature FeatIx) + r0(AVec Lb) +
           sgIxsV(UVec FeatIx) + obIxsV(Vec (AVec LbIx)) +
           prevIxsV(Vec (AVec LbIx)) + nextIxsV(Vec (AVec LbIx))

   Ob (external) = ([Int], Text) — observation
   Lb (internal) = Int — label ID
   FeatIx = Int — parameter index
   AVec a = UVector a — sorted vector
   LbIx = (Lb, FeatIx) — label + feature index pair"
  (:require [concraft.binary :as b]
            [concraft.dag :as dag]))

;; -- Observation type Ob = ([Int], Text) --

(defn- read-ob
  "Read an external observation Ob = ([Int], Text)."
  [dis]
  [(b/read-list dis b/read-int64)
   (b/read-text dis)])

;; -- AtomCodec --

(defn- read-atom-codec
  "Read AtomCodec a = {to :: Map a Int, from :: IntMap a}.
   read-elem reads one element of type 'a'."
  [dis read-elem]
  (let [to-map (b/read-map-unsorted dis read-elem b/read-int64)
        from-map (b/read-int-map dis read-elem)]
    {:to to-map
     :from from-map}))

;; -- Feature type --

(defn- read-feature
  "Read a Feature (SFeature | TFeature | OFeature).
   SFeature: tag=0, Lb
   TFeature: tag=1, (Lb, Lb)
   OFeature: tag=2, (Ob, Lb)"
  [dis]
  (let [tag (b/read-int64 dis)]
    (case tag
      0 {:type :s-feature :lb (b/read-int64 dis)}
      1 (let [lb1 (b/read-int64 dis)
              lb2 (b/read-int64 dis)]
          {:type :t-feature :lb1 lb1 :lb2 lb2})
      2 (let [ob (b/read-int64 dis)
              lb (b/read-int64 dis)]
          {:type :o-feature :ob ob :lb lb})
      (throw (ex-info "Unknown Feature tag" {:tag tag})))))

;; -- AVec (sorted unboxed vector) --

(defn- read-avec-long
  "Read AVec Lb = unboxed vector of longs."
  [dis]
  (b/read-uvector-int dis))

(defn- read-avec-pair
  "Read AVec (Lb, FeatIx) = unboxed vector of (Int, Int) pairs.
   Returns vector of [lb feat-ix] pairs."
  [dis]
  (b/read-uvector-pair dis b/read-int64 b/read-int64))

;; -- Model --

(defn- read-feat-ix
  "Read FeatIx (newtype over Int)."
  [dis]
  (b/read-int64 dis))

(defn- read-model
  "Read chain1 Model:
   values :: UVector Double
   ixMap :: Map Feature FeatIx
   r0 :: AVec Lb
   sgIxsV :: UVector FeatIx
   obIxsV :: Vector (AVec (Lb, FeatIx))
   prevIxsV :: Vector (AVec (Lb, FeatIx))
   nextIxsV :: Vector (AVec (Lb, FeatIx))"
  [dis]
  (let [values (b/read-uvector-double dis)
        ix-map (b/read-map-unsorted dis read-feature read-feat-ix)
        r0 (read-avec-long dis)
        sg-ixs-v (b/read-uvector-int dis)
        ob-ixs-v (b/read-vector dis read-avec-pair)
        prev-ixs-v (b/read-vector dis read-avec-pair)
        next-ixs-v (b/read-vector dis read-avec-pair)]
    {:values values
     :ix-map ix-map
     :r0 r0
     :sg-ixs-v sg-ixs-v
     :ob-ixs-v ob-ixs-v
     :prev-ixs-v prev-ixs-v
     :next-ixs-v next-ixs-v}))

;; -- CRF --

(defn read-crf
  "Read CRF a b = {codec :: (AtomCodec Ob, AtomCodec (Maybe b)), model :: Model}.
   read-label reads the label type 'b' (e.g., P.Tag for guesser)."
  [dis read-label]
  (let [;; Codec = (AtomCodec Ob, AtomCodec (Maybe b))
        ob-codec (read-atom-codec dis read-ob)
        label-codec (read-atom-codec dis
                      (fn [dis] (b/read-maybe dis read-label)))
        model (read-model dis)]
    {:ob-codec ob-codec
     :label-codec label-codec
     :model model}))

;; ============================================================
;; Inference: Forward-Backward on DAGs
;; Uses LogFloat arithmetic (values stored in log-domain).
;; ============================================================

;; -- LogFloat operations --
;; LogFloat stores log(x). Multiplication = addition in log.
;; Addition requires log-sum-exp for numerical stability.

(def ^:const ^double neg-inf Double/NEGATIVE_INFINITY)

(defn- log-sum-exp
  "Numerically stable log(sum(exp(xs)))."
  ^double [xs]
  (if (empty? xs)
    neg-inf
    (let [max-val (reduce max neg-inf xs)]
      (if (= max-val neg-inf)
        neg-inf
        (+ max-val
           (Math/log (reduce (fn ^double [^double acc ^double x]
                               (+ acc (Math/exp (- x max-val))))
                             0.0 xs)))))))

(defn- log-value
  "Get log-domain feature weight from model. FeatIx -1 means no feature = weight 0 = log(1) = 0."
  ^double [^doubles values ^long feat-ix]
  (if (neg? feat-ix)
    0.0  ;; log(1) = 0, i.e., feature weight = 1 (no effect)
    (aget values feat-ix)))

;; -- Sentence encoding --
;; Encode external (observations, labels) into internal integer IDs.

(defn encode-sent
  "Encode a sentence for CRF inference.
   For each edge: map external observations to internal Ob IDs,
   and external labels to internal Lb IDs.
   Returns a DAG with edge labels {:obs [Int], :lbs [Int]}
   where :obs are internal observation IDs and :lbs are internal label IDs.
   For OOV words (no labels), :lbs will be nil (use r0)."
  [crf schema-fn dag simplify-tag]
  (let [ob-codec-to (:to (:ob-codec crf))
        label-codec-to (:to (:label-codec crf))
        observations (into {} (map (fn [eid]
                                     [eid (schema-fn dag eid)]))
                           (dag/dag-edges dag))]
    (dag/map-e
     (fn [eid seg]
       (let [;; Map external observations to internal IDs
             ext-obs (get observations eid [])
             int-obs (keep (fn [ob] (get ob-codec-to ob)) ext-obs)
             ;; Map external labels to internal IDs (via Maybe wrapper)
             ext-tags (keys (:tags seg))
             int-lbs (when (seq ext-tags)
                       (keep (fn [tag]
                               (get label-codec-to (simplify-tag tag)))
                             ext-tags))]
         {:obs (vec int-obs)
          :lbs (when (seq int-lbs) (vec (sort (distinct int-lbs))))}))
     dag)))

;; -- Potential computation --

(defn- intersect-sorted
  "Merge-join two sorted sequences: pairs from `(Lb, FeatIx)` and labels `Lb`.
   Returns [(label-index, feat-ix)] where label-index is the position
   of the label in the labels vector."
  [lb-feat-pairs labels]
  (let [label-set (into {} (map-indexed (fn [i lb] [lb i])) labels)]
    (persistent!
     (reduce (fn [acc [lb feat-ix]]
               (if-let [idx (get label-set lb)]
                 (conj! acc [idx feat-ix])
                 acc))
             (transient [])
             lb-feat-pairs))))

(defn- compute-psi
  "Compute observation potential ψ(edge, label-index) in log-domain.
   Returns a double array indexed by label-index."
  [model int-obs labels]
  (let [^doubles values (:values model)
        n (count labels)
        ^doubles psi (double-array n 0.0)]  ;; Initialize to 0.0 = log(1)
    ;; For each observation, find matching (label-ix, feat-ix) pairs
    (doseq [ob int-obs]
      (when (< ob (count (:ob-ixs-v model)))
        (let [ob-pairs (nth (:ob-ixs-v model) ob)]
          (doseq [[label-ix feat-ix] (intersect-sorted ob-pairs labels)]
            (when (< label-ix n)
              (aset psi label-ix
                    (+ (aget psi label-ix)
                       (log-value values feat-ix))))))))
    psi))

;; -- Label set for an edge --

(defn- edge-labels
  "Get the label set for an edge.
   Returns a vector of internal label IDs (sorted).
   For unconstrained (OOV) edges, uses r0."
  [model encoded-edge]
  (or (:lbs encoded-edge)
      (vec (seq (:r0 model)))))

;; -- Forward algorithm --

(defn- forward
  "Compute forward table α(edge-id, label-index) in log-domain.
   Returns a map {edge-id → double-array}."
  [model encoded-dag]
  (let [^doubles values (:values model)
        edges (dag/dag-edges encoded-dag)
        initial-set (set (filter #(dag/initial-edge? encoded-dag %) edges))
        ;; End sentinel key
        end-key :end
        ;; Compute α for each edge in topological order
        alpha (reduce
               (fn [alpha eid]
                 (let [enc (dag/edge-label encoded-dag eid)
                       labels (edge-labels model enc)
                       n (count labels)
                       psi (compute-psi model (:obs enc) labels)
                       ^doubles a (double-array n)]
                   (if (contains? initial-set eid)
                     ;; Initial edge: α(i,j) = ψ(i,j) × sgValue(label_j)
                     (dotimes [j n]
                       (let [lb (nth labels j)
                             sg-ix (aget ^longs (:sg-ixs-v model) lb)
                             sg-val (log-value values sg-ix)]
                         (aset a j (+ (aget psi j) sg-val))))
                     ;; Non-initial: α(i,j) = ψ(i,j) × ((u - v) + w)
                     ;; But in log-domain: log(ψ) + log((u - v) + w)
                     ;; Need to work in linear domain for the (u-v)+w trick
                     (let [prev-eids (dag/prev-edges encoded-dag eid)]
                       (dotimes [j n]
                         (let [x (nth labels j)
                               ;; u = sum of all α(prev, k) across all prev edges
                               u-terms (for [pe prev-eids
                                             :let [prev-labels (edge-labels model (dag/edge-label encoded-dag pe))
                                                   prev-alpha (get alpha pe)]
                                             :when (do (when (nil? prev-alpha)
                                                         (throw (ex-info "Missing alpha for prev edge"
                                                                         {:eid eid :pe pe :alpha-keys (keys alpha)})))
                                                       true)
                                             k (range (count prev-labels))]
                                         (aget ^doubles prev-alpha k))
                               u (log-sum-exp (vec u-terms))
                               ;; v = sum of α(prev, k) where prev-label has transition to x
                               ;; w = sum of α(prev, k) × transition_weight(prev→x)
                               prev-pairs (nth (:prev-ixs-v model) x)
                               vw (for [pe prev-eids
                                        :let [prev-labels (edge-labels model (dag/edge-label encoded-dag pe))
                                              ^doubles prev-alpha (get alpha pe)]
                                        [k ix] (intersect-sorted prev-pairs prev-labels)]
                                    [(aget prev-alpha k) (+ (aget prev-alpha k) (log-value values ix))])
                               v (log-sum-exp (mapv first vw))
                               w (log-sum-exp (mapv second vw))
                               ;; (u - v) + w in log-domain: log(exp(u) - exp(v) + exp(w))
                               ;; Use linear for the subtraction
                               uv-plus-w (Math/log (+ (- (Math/exp u) (Math/exp v))
                                                      (Math/exp w)))]
                           (aset a j (+ (aget psi j) uv-plus-w))))))
                   (assoc alpha eid a)))
               {}
               edges)
        ;; End sentinel: sum of all α at final edges
        final-eids (filter #(dag/final-edge? encoded-dag %) edges)
        end-val (log-sum-exp
                 (vec (for [fe final-eids
                            :let [^doubles a (get alpha fe)
                                  labels (edge-labels model (dag/edge-label encoded-dag fe))]
                            k (range (count labels))]
                        (aget a k))))]
    (assoc alpha end-key (double-array [end-val]))))

;; -- Backward algorithm --

(defn- backward
  "Compute backward table β(edge-id, label-index) in log-domain.
   Returns a map {edge-id → double-array}."
  [model encoded-dag]
  (let [^doubles values (:values model)
        edges (dag/dag-edges encoded-dag)
        final-set (set (filter #(dag/final-edge? encoded-dag %) edges))
        beg-key :beg
        ;; Precompute psi for all edges
        psi-cache (into {}
                        (map (fn [eid]
                               (let [enc (dag/edge-label encoded-dag eid)
                                     labels (edge-labels model enc)]
                                 [eid (compute-psi model (:obs enc) labels)])))
                        edges)
        ;; Compute β for each edge in reverse topological order
        beta (reduce
              (fn [beta eid]
                (let [enc (dag/edge-label encoded-dag eid)
                      labels (edge-labels model enc)
                      n (count labels)
                      ^doubles b (double-array n)]
                  (if (contains? final-set eid)
                    ;; Final edge: β(i,j) = 1 → log(1) = 0
                    (java.util.Arrays/fill b 0.0)
                    ;; Non-final: β(i,j) = (u - v) + w
                    (let [next-eids (dag/next-edges encoded-dag eid)]
                      (dotimes [j n]
                        (let [y (nth labels j)
                              ;; u = sum of β(next,k) × ψ(next,k)
                              u-terms (for [ne next-eids
                                            :let [next-labels (edge-labels model (dag/edge-label encoded-dag ne))
                                                  ^doubles next-beta (get beta ne)
                                                  ^doubles next-psi (get psi-cache ne)]
                                            k (range (count next-labels))]
                                        (+ (aget next-beta k) (aget next-psi k)))
                              u (log-sum-exp (vec u-terms))
                              ;; v, w with transition features
                              next-pairs (nth (:next-ixs-v model) y)
                              vw (for [ne next-eids
                                       :let [next-labels (edge-labels model (dag/edge-label encoded-dag ne))
                                             ^doubles next-beta (get beta ne)
                                             ^doubles next-psi (get psi-cache ne)]
                                       [k ix] (intersect-sorted next-pairs next-labels)]
                                   [(+ (aget next-beta k) (aget next-psi k))
                                    (+ (aget next-beta k) (aget next-psi k) (log-value values ix))])
                              v (log-sum-exp (mapv first vw))
                              w (log-sum-exp (mapv second vw))
                              uv-plus-w (Math/log (+ (- (Math/exp u) (Math/exp v))
                                                     (Math/exp w)))]
                          (aset b j uv-plus-w)))))
                  (assoc beta eid b)))
              {}
              (reverse edges))
        ;; Beg sentinel
        initial-eids (filter #(dag/initial-edge? encoded-dag %) edges)
        beg-val (log-sum-exp
                 (vec (for [ie initial-eids
                            :let [^doubles b (get beta ie)
                                  ^doubles p (get psi-cache ie)
                                  enc (dag/edge-label encoded-dag ie)
                                  labels (edge-labels model enc)]
                            k (range (count labels))
                            :let [lb (nth labels k)
                                  sg-ix (aget ^longs (:sg-ixs-v model) lb)]]
                        (+ (aget b k) (aget p k) (log-value values sg-ix)))))]
    (assoc beta beg-key (double-array [beg-val]))))

;; -- Marginals --

(defn marginals
  "Compute marginal probabilities P(label | sentence) for each edge.
   Returns a map {edge-id → [(label-id, probability)]}."
  [model encoded-dag]
  (let [alpha (forward model encoded-dag)
        beta (backward model encoded-dag)
        z-alpha (aget ^doubles (get alpha :end) 0)
        z-beta (aget ^doubles (get beta :beg) 0)]
    ;; Verify normalization factors approximately match
    (when (> (abs (- z-alpha z-beta)) 1e-3)
      (println "[marginals] WARNING: Z mismatch:" z-alpha z-beta))
    (let [z z-beta]
      (into {}
            (map (fn [eid]
                   (let [enc (dag/edge-label encoded-dag eid)
                         labels (edge-labels model enc)
                         ^doubles a (get alpha eid)
                         ^doubles b (get beta eid)]
                     [eid (mapv (fn [k]
                                 (let [lb (nth labels k)
                                       prob (Math/exp (- (+ (aget a k) (aget b k)) z))]
                                   [lb prob]))
                               (range (count labels)))])))
            (dag/dag-edges encoded-dag)))))

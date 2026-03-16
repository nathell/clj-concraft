(ns concraft.crf.chain2
  "CRF chain2 tiers model reader and inference.

   Binary format:
   CRF = numOfLayers(Int) + codec + model
   Codec = (AtomCodec Ob, Vector (AtomCodec (Maybe b)))
   AtomCodec a = (Map a Int, IntMap a)
   Model = values(UVec Double) + featMap(Vec LayerMap)
   LayerMap = t1Map + t2Map + t3Map + obMap

   In chain2:
   Ob = Int32 (4 bytes)
   Lb = Int16 (2 bytes)
   FeatIx = Int32 (4 bytes)
   Cb = UVector Lb (complex label)"
  (:require [concraft.binary :as b]
            [concraft.dag :as dag]))

;; -- Observation type Ob = ([Int], Text) for external codec --

(defn- read-ob
  "Read an external observation Ob = ([Int], Text)."
  [dis]
  [(b/read-list dis b/read-int64)
   (b/read-text dis)])

;; -- AtomCodec (same as chain1) --

(defn- read-atom-codec
  "Read AtomCodec a = {to :: Map a Int, from :: IntMap a}."
  [dis read-elem]
  (let [to-map (b/read-map-unsorted dis read-elem b/read-int64)
        from-map (b/read-int-map dis read-elem)]
    {:to to-map
     :from from-map}))

;; -- Array type: Array i a = {bounds :: (i, i), array :: UVector a} --

(defn- read-array-1d
  "Read Array Lb FeatIx (T1Map).
   bounds = (Lb, Lb) where Lb is Int16.
   array = UVector FeatIx where FeatIx is Int32."
  [dis]
  (let [lo (b/read-int16 dis)
        hi (b/read-int16 dis)
        arr (b/read-uvector-int32 dis)]
    {:lo lo :hi hi :arr arr}))

(defn- read-array-2d
  "Read Array (Lb,Lb) FeatIx (T2Map).
   bounds = ((Lb,Lb), (Lb,Lb)).
   array = UVector FeatIx."
  [dis]
  (let [lo1 (b/read-int16 dis)
        lo2 (b/read-int16 dis)
        hi1 (b/read-int16 dis)
        hi2 (b/read-int16 dis)
        arr (b/read-uvector-int32 dis)]
    {:lo [lo1 lo2] :hi [hi1 hi2] :arr arr}))

(defn- read-array-3d
  "Read Array (Lb,Lb,Lb) FeatIx (T3Map).
   bounds = ((Lb,Lb,Lb), (Lb,Lb,Lb)).
   array = UVector FeatIx."
  [dis]
  (let [lo1 (b/read-int16 dis)
        lo2 (b/read-int16 dis)
        lo3 (b/read-int16 dis)
        hi1 (b/read-int16 dis)
        hi2 (b/read-int16 dis)
        hi3 (b/read-int16 dis)
        arr (b/read-uvector-int32 dis)]
    {:lo [lo1 lo2 lo3] :hi [hi1 hi2 hi3] :arr arr}))

;; -- OMap --

(defn- read-omap
  "Read OMap = {oBeg :: UVector Int32, oLb :: UVector Lb(Int16), oIx :: UVector FeatIx(Int32)}."
  [dis]
  {:o-beg (b/read-uvector-int32 dis)
   :o-lb (b/read-uvector-int dis)     ;; Lb is Int16 but stored in UVector; need special reader
   :o-ix (b/read-uvector-int32 dis)})

;; Actually, Lb = Int16, so UVector Lb needs a short vector reader.
;; Let me add one.

(defn- read-uvector-int16
  "Read a Haskell unboxed Vector Int16."
  [dis]
  (let [n (b/read-int64 dis)
        arr (short-array n)]
    (dotimes [i n]
      (aset arr i (short (b/read-int16 dis))))
    arr))

;; Fix OMap to use Int16 for Lb
(defn- read-omap-fixed
  "Read OMap = {oBeg :: UVector Int32, oLb :: UVector Lb(Int16), oIx :: UVector FeatIx(Int32)}."
  [dis]
  {:o-beg (b/read-uvector-int32 dis)
   :o-lb (read-uvector-int16 dis)
   :o-ix (b/read-uvector-int32 dis)})

;; -- LayerMap --

(defn- read-layer-map
  "Read LayerMap = {t1Map, t2Map, t3Map, obMap}."
  [dis]
  {:t1-map (read-array-1d dis)
   :t2-map (read-array-2d dis)
   :t3-map (read-array-3d dis)
   :ob-map (read-omap-fixed dis)})

;; -- Model --

(defn- read-model
  "Read chain2 Model = {values :: UVector Double, featMap :: Vector LayerMap}."
  [dis]
  {:values (b/read-uvector-double dis)
   :feat-map (b/read-vector dis read-layer-map)})

;; -- CRF --

(defn read-crf
  "Read CRF a b = {numOfLayers :: Int, codec, model}.
   Codec = (AtomCodec Ob, Vector (AtomCodec (Maybe b)))
   read-label reads the label type 'b' (e.g., Atom for disamb)."
  [dis read-label]
  (let [num-layers (b/read-int64 dis)
        ;; Codec = (AtomCodec Ob, Vector (AtomCodec (Maybe b)))
        ob-codec (read-atom-codec dis read-ob)
        label-codecs (b/read-vector dis
                       (fn [dis]
                         (read-atom-codec dis
                           (fn [dis] (b/read-maybe dis read-label)))))
        model (read-model dis)]
    {:num-layers num-layers
     :ob-codec ob-codec
     :label-codecs label-codecs
     :model model}))

;; ============================================================
;; Inference: Second-order forward-backward on DAGs with tiers
;; ============================================================

(def ^:const ^double neg-inf Double/NEGATIVE_INFINITY)

(defn log-sum-exp ^double [xs]
  (if (empty? xs)
    neg-inf
    (let [max-val (reduce max neg-inf xs)]
      (if (= max-val neg-inf)
        neg-inf
        (+ max-val (Math/log (reduce (fn ^double [^double acc ^double x]
                                       (+ acc (Math/exp (- x max-val))))
                                     0.0 xs)))))))

;; -- Feature lookup in chain2 Model --

(defn- array-lookup-1d
  "Look up in Array Lb FeatIx. Returns feat-ix or -1."
  ^long [{:keys [lo hi ^ints arr]} ^long lb]
  (let [idx (- lb lo)]
    (if (and (>= idx 0) (< idx (alength arr)))
      (aget arr idx)
      -1)))

(defn- array-lookup-2d
  "Look up in Array (Lb,Lb) FeatIx. Returns feat-ix or -1."
  ^long [{:keys [lo hi ^ints arr]} ^long lb1 ^long lb2]
  (let [[lo1 lo2] lo
        [hi1 hi2] hi
        d2 (- hi2 lo2 -1)
        idx (+ (* (- lb1 lo1) d2) (- lb2 lo2))]
    (if (and (>= idx 0) (< idx (alength arr)))
      (aget arr idx)
      -1)))

(defn- array-lookup-3d
  "Look up in Array (Lb,Lb,Lb) FeatIx. Returns feat-ix or -1."
  ^long [{:keys [lo hi ^ints arr]} ^long lb1 ^long lb2 ^long lb3]
  (let [[lo1 lo2 lo3] lo
        [hi1 hi2 hi3] hi
        d3 (- hi3 lo3 -1)
        d2 (- hi2 lo2 -1)
        idx (+ (* (+ (* (- lb1 lo1) d2) (- lb2 lo2)) d3) (- lb3 lo3))]
    (if (and (>= idx 0) (< idx (alength arr)))
      (aget arr idx)
      -1)))

(defn- phi
  "Get log-domain feature weight. Returns 0.0 (= log(1)) if feat-ix = -1."
  ^double [^doubles values ^long feat-ix]
  (if (neg? feat-ix)
    0.0
    (aget values feat-ix)))

;; -- Observation potential (ψ) --

(defn- on-word
  "Compute observation potential ψ for an edge-label combination in log-domain.
   edgeIx = {:edge-id EdgeID, :lb-ix CbIx}
   X at edge has observations and labels (Cb = vector of atomic Lb per tier)."
  [model encoded-dag {:keys [edge-id lb-ix]}]
  (let [^doubles values (:values model)
        enc (dag/edge-label encoded-dag edge-id)
        obs (:obs enc)
        labels (:lbs enc)
        cb (nth labels lb-ix)  ;; vector of atomic labels, one per layer
        feat-map (:feat-map model)]
    ;; ψ = ∏ phi(OFeat(ob, lb_k, k)) for all obs, all layers k
    (reduce (fn ^double [^double acc ^long ob]
              (reduce (fn ^double [^double acc2 ^long k]
                        (let [layer (nth feat-map k)
                              ob-map (:ob-map layer)
                              ;; Look up OFeat in OMap: need to find entry for (ob, lb)
                              ;; OMap = {oBeg: UVec Int32, oLb: UVec Int16, oIx: UVec FeatIx}
                              ;; oBeg[ob] gives start index, oBeg[ob+1] gives end
                              ^ints o-beg (:o-beg ob-map)
                              ^shorts o-lb (:o-lb ob-map)
                              ^ints o-ix (:o-ix ob-map)
                              lb (nth cb k)]
                          (if (>= ob (dec (alength o-beg)))
                            acc2
                            (let [start (aget o-beg ob)
                                  end (aget o-beg (inc ob))]
                              ;; Binary search or linear scan for lb in o-lb[start..end)
                              (loop [i start, result acc2]
                                (if (>= i end)
                                  result
                                  (if (= (aget o-lb i) lb)
                                    (+ result (phi values (aget o-ix i)))
                                    (recur (inc i) result))))))))
                      acc
                      (range (count feat-map))))
            0.0
            obs)))

;; -- Transition potential --

(defn- on-transition
  "Compute transition potential in log-domain.
   u, v, w are Maybe EdgeIx (nil = boundary sentinel)."
  [model encoded-dag u v w]
  (let [^doubles values (:values model)
        feat-map (:feat-map model)
        n-layers (count feat-map)]
    (reduce (fn ^double [^double acc ^long k]
              (let [layer (nth feat-map k)
                    u-lb (when u (nth (:lbs (dag/edge-label encoded-dag (:edge-id u))) (:lb-ix u)))
                    v-lb (when v (nth (:lbs (dag/edge-label encoded-dag (:edge-id v))) (:lb-ix v)))
                    w-lb (when w (nth (:lbs (dag/edge-label encoded-dag (:edge-id w))) (:lb-ix w)))
                    u-lb-k (when u-lb (nth u-lb k))
                    v-lb-k (when v-lb (nth v-lb k))
                    w-lb-k (when w-lb (nth w-lb k))]
                (cond
                  ;; All three known: TFeat3
                  (and u-lb-k v-lb-k w-lb-k)
                  (+ acc (phi values (array-lookup-3d (:t3-map layer) u-lb-k v-lb-k w-lb-k))
                         (phi values (array-lookup-2d (:t2-map layer) u-lb-k v-lb-k))
                         (phi values (array-lookup-1d (:t1-map layer) u-lb-k)))

                  ;; Two known: TFeat2
                  (and u-lb-k v-lb-k)
                  (+ acc (phi values (array-lookup-2d (:t2-map layer) u-lb-k v-lb-k))
                         (phi values (array-lookup-1d (:t1-map layer) u-lb-k)))

                  ;; One known: TFeat1
                  u-lb-k
                  (+ acc (phi values (array-lookup-1d (:t1-map layer) u-lb-k)))

                  :else acc)))
            0.0
            (range n-layers))))

;; -- Encode sentence for chain2 --

(defn encode-sent
  "Encode a sentence for chain2 CRF inference.
   Each edge gets {:obs [Int], :lbs [[atomic-lb-per-layer]]}
   where obs are internal observation IDs and lbs are vectors of Cb
   (each Cb = vector of atomic label IDs per layer)."
  [crf schema-fn dag simplify-tag split-fn]
  (let [ob-codec-to (:to (:ob-codec crf))
        label-codecs (:label-codecs crf)
        n-layers (:num-layers crf)
        observations (into {} (map (fn [eid] [eid (schema-fn dag eid)]))
                           (dag/dag-edges dag))]
    (dag/map-e
     (fn [eid seg]
       (let [;; Map external observations to internal IDs
             ext-obs (get observations eid [])
             int-obs (vec (keep #(get ob-codec-to %) ext-obs))
             ;; Map external labels to internal Cb (vector of atomic IDs per layer)
             ext-tags (keys (:tags seg))
             int-lbs (vec (distinct
                            (keep (fn [tag]
                                    (let [atoms (split-fn (simplify-tag tag))]
                                      (when (= (count atoms) n-layers)
                                        (vec (map-indexed
                                               (fn [k atom]
                                                 (let [codec (nth label-codecs k)]
                                                   (get (:to codec) atom)))
                                               atoms)))))
                                  ext-tags)))]
         {:obs int-obs
          :lbs (vec (remove #(some nil? %) int-lbs))}))
     dag)))

;; -- Edge iteration helpers --

(defn- edge-ixs
  "All EdgeIx combinations for an edge."
  [encoded-dag edge-id]
  (let [n (count (:lbs (dag/edge-label encoded-dag edge-id)))]
    (mapv (fn [i] {:edge-id edge-id :lb-ix i}) (range n))))

(defn- prev-edge-ixs
  "Previous EdgeIxs (or [nil] for boundary)."
  [encoded-dag maybe-edge-id]
  (if (nil? maybe-edge-id)
    [nil]
    (let [prevs (dag/prev-edges encoded-dag maybe-edge-id)]
      (if (empty? prevs)
        [nil]
        (vec (mapcat #(edge-ixs encoded-dag %) prevs))))))

;; -- Forward-backward with memoization --

(defn- make-pos-key
  "Create a cache key from two Pos values."
  [u v]
  [u v])

(defn marginals
  "Compute marginal probabilities for chain2 CRF.
   acc-fn is the accumulation function: log-sum-exp for marginals, max for max-probs.
   Returns {edge-id → [(cb-ix, probability)]}."
  [model encoded-dag acc-fn]
  (let [edges (dag/dag-edges encoded-dag)
        ;; Memoize psi (observation potential)
        psi-cache (into {}
                        (mapcat (fn [eid]
                                  (map (fn [eix]
                                         [eix (on-word model encoded-dag eix)])
                                       (edge-ixs encoded-dag eid))))
                        edges)
        psi (fn ^double [eix] (get psi-cache eix 0.0))

        ;; Forward computation with memoization
        alpha-cache (atom {})
        alpha (fn alpha [u v]
                (if-let [cached (get @alpha-cache (make-pos-key u v))]
                  cached
                  (let [result
                        (cond
                          ;; Base case: α(Beg, Beg) = 0.0 = log(1)
                          (and (= u :beg) (= v :beg))
                          0.0

                          ;; End: α(End, End)
                          (and (= u :end) (= v :end))
                          (let [final-eids (filter #(dag/final-edge? encoded-dag %) edges)]
                            (acc-fn (for [w (mapcat #(edge-ixs encoded-dag %) final-eids)]
                                      (+ (alpha :end [:mid w])
                                         (on-transition model encoded-dag nil nil w)))))

                          ;; General: α(u, v)
                          :else
                          (let [v-eid (when (vector? v) (:edge-id (second v)))
                                prev-eixs (prev-edge-ixs encoded-dag v-eid)]
                            (acc-fn
                             (for [w prev-eixs]
                               (let [w-pos (if w [:mid w] :beg)]
                                 (+ (alpha v w-pos)
                                    (if (and (vector? u) (= (first u) :mid))
                                      (psi (second u))
                                      0.0)
                                    (on-transition model encoded-dag
                                                   (when (and (vector? u) (= (first u) :mid)) (second u))
                                                   (when (and (vector? v) (= (first v) :mid)) (second v))
                                                   (when w w))))))))]
                    (swap! alpha-cache assoc (make-pos-key u v) result)
                    result)))

        ;; Backward computation with memoization
        beta-cache (atom {})
        beta (fn beta [v w]
               (if-let [cached (get @beta-cache (make-pos-key v w))]
                 cached
                 (let [result
                       (cond
                         ;; Base: β(End, End) = 0.0 = log(1)
                         (and (= v :end) (= w :end))
                         0.0

                         ;; Beg: β(Beg, Beg)
                         (and (= v :beg) (= w :beg))
                         (let [initial-eids (filter #(dag/initial-edge? encoded-dag %) edges)]
                           (acc-fn (for [u (mapcat #(edge-ixs encoded-dag %) initial-eids)]
                                     (+ (beta [:mid u] :beg)
                                        (psi u)
                                        (on-transition model encoded-dag u nil nil)))))

                         ;; General: β(v, w)
                         :else
                         (let [v-eid (when (vector? v) (:edge-id (second v)))
                               next-eixs (if v-eid
                                           (let [nexts (dag/next-edges encoded-dag v-eid)]
                                             (if (empty? nexts)
                                               [nil]
                                               (vec (mapcat #(edge-ixs encoded-dag %) nexts))))
                                           [nil])]
                           (acc-fn
                            (for [u next-eixs]
                              (let [u-pos (if u [:mid u] :end)]
                                (+ (beta u-pos v)
                                   (if u (psi u) 0.0)
                                   (on-transition model encoded-dag
                                                  (when u u)
                                                  (when (and (vector? v) (= (first v) :mid)) (second v))
                                                  (when (and (vector? w) (= (first w) :mid)) (second w)))))))))]
                   (swap! beta-cache assoc (make-pos-key v w) result)
                   result)))

        ;; Compute Z
        z-alpha (alpha :end :end)
        z-beta (beta :beg :beg)]

    (when (> (abs (- z-alpha z-beta)) 1.0)
      (println "[chain2/marginals] WARNING: Z mismatch:" z-alpha z-beta))

    ;; Compute marginal P(edge, label)
    (into {}
          (map (fn [eid]
                 (let [eixs (edge-ixs encoded-dag eid)]
                   [eid (mapv (fn [eix]
                                (let [u [:mid eix]
                                      ;; P(u) = sum_v edgeProb2(u, v)
                                      prev-eixs (prev-edge-ixs encoded-dag eid)
                                      prob (acc-fn
                                            (for [v prev-eixs]
                                              (let [v-pos (if v [:mid v] :beg)]
                                                (- (+ (alpha u v-pos)
                                                      (beta u v-pos))
                                                   z-alpha))))]
                                  [(:lb-ix eix) (Math/exp prob)]))
                              eixs)])))
          edges)))

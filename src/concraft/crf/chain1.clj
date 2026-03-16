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
  (:require [concraft.binary :as b]))

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

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
  (:require [concraft.binary :as b]))

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

(ns concraft.binary
  "Reader for Haskell's Data.Binary serialization format.
   All readers take a DataInputStream as first argument."
  (:import [java.io DataInputStream BufferedInputStream]
           [java.util.zip GZIPInputStream]))

;; -- Primitive readers --

(defn read-word8
  "Read an unsigned byte."
  ^long [^DataInputStream dis]
  (.readUnsignedByte dis))

(defn read-int16
  "Read a signed 16-bit big-endian integer."
  ^long [^DataInputStream dis]
  (long (.readShort dis)))

(defn read-int32
  "Read a signed 32-bit big-endian integer."
  ^long [^DataInputStream dis]
  (long (.readInt dis)))

(defn read-int64
  "Read a signed 64-bit big-endian integer (Haskell Int)."
  ^long [^DataInputStream dis]
  (.readLong dis))

(defn read-double-ieee
  "Read a big-endian IEEE 754 double."
  ^double [^DataInputStream dis]
  (.readDouble dis))

;; -- Haskell Integer (decodeFloat encoding) --

(defn- read-integer
  "Read a Haskell Integer in Data.Binary format:
   Tag 0 = small (fits in Int32): Word8(0) + Int32(value)
   Tag 1 = large: Word8(1) + Word8(sign) + [Word8](LE abs bytes)"
  [^DataInputStream dis]
  (let [tag (read-word8 dis)]
    (case (int tag)
      0 (long (read-int32 dis))
      1 (let [sign-byte (read-word8 dis)
              ;; sign-byte: 0x01 = positive, 0xFF = negative, 0x00 = zero
              sign (cond
                     (= sign-byte 1) 1
                     (= sign-byte 255) -1
                     :else 0)
              n-bytes (read-int64 dis)
              ;; Read LE bytes and reconstruct BigInteger
              bs (byte-array n-bytes)]
          (.readFully dis bs)
          ;; bytes are in little-endian, BigInteger needs big-endian
          (let [be-bs (byte-array n-bytes)]
            (dotimes [i n-bytes]
              (aset be-bs i (aget bs (- n-bytes 1 i))))
            (let [abs-val (BigInteger. 1 be-bs)]
              (if (neg? sign)
                (.negate abs-val)
                abs-val))))
      (throw (ex-info "Invalid Integer tag" {:tag tag})))))

(defn read-double
  "Read a Haskell Double in decodeFloat encoding: (Integer, Int) pair.
   Double = encodeFloat significand exponent."
  ^double [^DataInputStream dis]
  (let [significand (read-integer dis)
        exponent (read-int64 dis)]
    (Math/scalb (double significand) (int exponent))))

(defn read-bool
  "Read a boolean (1 byte: 0=false, 1=true)."
  [^DataInputStream dis]
  (not (zero? (read-word8 dis))))

;; -- UTF-8 char reading (for Haskell Char Binary instance) --

(defn read-char
  "Read a single UTF-8 encoded character (Haskell Char Binary instance).
   putCharUtf8 writes 1-4 bytes depending on the code point."
  [^DataInputStream dis]
  (let [b0 (read-word8 dis)]
    (cond
      (< b0 0x80)
      (char b0)

      (< b0 0xC0)
      (throw (ex-info "Invalid UTF-8 continuation byte at start" {:byte b0}))

      (< b0 0xE0)
      (let [b1 (read-word8 dis)]
        (char (bit-or (bit-shift-left (bit-and b0 0x1F) 6)
                      (bit-and b1 0x3F))))

      (< b0 0xF0)
      (let [b1 (read-word8 dis)
            b2 (read-word8 dis)]
        (char (bit-or (bit-shift-left (bit-and b0 0x0F) 12)
                      (bit-shift-left (bit-and b1 0x3F) 6)
                      (bit-and b2 0x3F))))

      :else
      (let [b1 (read-word8 dis)
            b2 (read-word8 dis)
            b3 (read-word8 dis)
            cp (bit-or (bit-shift-left (bit-and b0 0x07) 18)
                       (bit-shift-left (bit-and b1 0x3F) 12)
                       (bit-shift-left (bit-and b2 0x3F) 6)
                       (bit-and b3 0x3F))]
        ;; Code points above U+FFFF need surrogate pairs in Java
        (if (> cp 0xFFFF)
          (let [cp' (- cp 0x10000)
                hi (char (+ 0xD800 (bit-shift-right cp' 10)))
                lo (char (+ 0xDC00 (bit-and cp' 0x3FF)))]
            ;; Return as a string for supplementary chars
            (str hi lo))
          (char cp))))))

;; -- Compound readers --

(defn read-string-haskell
  "Read a Haskell String (= [Char]): Int64 length + UTF-8 chars."
  [^DataInputStream dis]
  (let [n (read-int64 dis)
        sb (StringBuilder.)]
    (dotimes [_ n]
      (let [c (read-char dis)]
        (if (string? c)
          (.append sb ^String c)
          (.append sb ^char c))))
    (.toString sb)))

(defn read-text
  "Read a Haskell Text via text-binary: Int64 byte-length + UTF-8 bytes."
  [^DataInputStream dis]
  (let [n (read-int64 dis)
        bs (byte-array n)]
    (.readFully dis bs)
    (String. bs "UTF-8")))

(defn read-list
  "Read a Haskell list: Int64 length + elements read by `read-elem`."
  [^DataInputStream dis read-elem]
  (let [n (read-int64 dis)]
    (loop [i 0 acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (recur (inc i) (conj! acc (read-elem dis)))))))

(defn read-maybe
  "Read a Haskell Maybe: Word8 tag (0=Nothing, 1=Just) + value."
  [^DataInputStream dis read-val]
  (let [tag (read-word8 dis)]
    (case (int tag)
      0 nil
      1 (read-val dis)
      (throw (ex-info "Invalid Maybe tag" {:tag tag})))))

(defn read-pair
  "Read a Haskell pair (a, b)."
  [^DataInputStream dis read-a read-b]
  [(read-a dis) (read-b dis)])

(defn read-map
  "Read a Haskell Map (serialized as list of pairs).
   Returns a Clojure sorted-map."
  [^DataInputStream dis read-key read-val]
  (let [pairs (read-list dis (fn [dis] (read-pair dis read-key read-val)))]
    (into (sorted-map) pairs)))

(defn read-map-unsorted
  "Read a Haskell Map as an unsorted Clojure hash-map."
  [^DataInputStream dis read-key read-val]
  (let [pairs (read-list dis (fn [dis] (read-pair dis read-key read-val)))]
    (into {} pairs)))

(defn read-set
  "Read a Haskell Set (serialized as sorted list).
   Returns a Clojure set."
  [^DataInputStream dis read-elem]
  (let [elems (read-list dis read-elem)]
    (set elems)))

(defn read-int-map
  "Read a Haskell IntMap (serialized as list of (Int, v) pairs).
   Returns a Clojure hash-map with integer keys."
  [^DataInputStream dis read-val]
  (let [pairs (read-list dis (fn [dis] (read-pair dis read-int64 read-val)))]
    (into {} pairs)))

(defn read-vector
  "Read a Haskell Vector (boxed): Int64 length + elements."
  [^DataInputStream dis read-elem]
  (read-list dis read-elem))

(defn read-uvector-double
  "Read a Haskell unboxed Vector Double: Int64 length + doubles.
   Returns a Java double array for O(1) access."
  ^doubles [^DataInputStream dis]
  (let [n (read-int64 dis)
        arr (double-array n)]
    (dotimes [i n]
      (aset arr i (read-double dis)))
    arr))

(defn read-uvector-int
  "Read a Haskell unboxed Vector Int: Int64 length + Int64s.
   Returns a Java long array."
  ^longs [^DataInputStream dis]
  (let [n (read-int64 dis)
        arr (long-array n)]
    (dotimes [i n]
      (aset arr i (read-int64 dis)))
    arr))

(defn read-uvector-int32
  "Read a Haskell unboxed Vector Int32: Int64 length + Int32s.
   Returns a Java int array."
  ^ints [^DataInputStream dis]
  (let [n (read-int64 dis)
        arr (int-array n)]
    (dotimes [i n]
      (aset arr i (int (read-int32 dis))))
    arr))

(defn read-uvector-pair
  "Read a Haskell unboxed Vector of pairs: Int64 length + pairs.
   Each element is read by read-a and read-b."
  [^DataInputStream dis read-a read-b]
  (let [n (read-int64 dis)]
    (loop [i 0 acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (let [a (read-a dis)
              b (read-b dis)]
          (recur (inc i) (conj! acc [a b])))))))

;; -- Stream construction --

(defn open-gzip-binary
  "Open a gzip-compressed binary file for reading with Haskell Binary format.
   Returns a DataInputStream."
  ^DataInputStream [path]
  (-> (java.io.FileInputStream. (str path))
      (BufferedInputStream.)
      (GZIPInputStream.)
      (BufferedInputStream. (* 256 1024))
      (DataInputStream.)))

(defn skip-bytes
  "Skip n bytes in the stream."
  [^DataInputStream dis ^long n]
  (.skipBytes dis (int n)))

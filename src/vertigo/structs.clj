(ns vertigo.structs
  (:use
    potemkin)
  (:require
    [clojure.core.protocols :as proto]
    [vertigo.bytes :as b]
    [vertigo.primitives :as p]))

;;;

(definterface+ IFixedType
  (byte-size ^long [_]
    "The size of the primitive type, in bytes.")
  (write-value [_ byte-seq ^long offset x]
    "Writes the value to a byte-seq at the given byte offset.")
  (read-value [_ byte-seq ^long offset]
    "Reads the value from a byte-seq at the given byte-offset."))

(definterface+ IFixedCompoundType
  (has-field? [_ x]
    "Returns true if the type has the given field, false otherwise")
  (field-offset ^long [_ x]
    "Returns of the offset of the field within the struct, in bytes.")
  (field-type [_ x]
    "Returns the type of the field."))

(definterface+ IFixedInlinedType
  (read-form [_ byte-seq idx]
    "Returns an eval'able form for reading the struct off a byte-seq.")
  (write-form [_ byte-seq idx val]
    "Returns an eval'able form for writing the struct to a byte-seq."))

(definterface+ IByteSeqWrapper
  (index-offset ^long [_ ^long idx]
    "Returns the byte offset of the given index within the byte-seq-wrapper.")
  (element-type [_]
    "Returns the type of the elements within the seq.")
  (unwrap-byte-seq [_]
    "Returns the byte-seq within the wrapper."))

;;;

(defn- inner-type
  [type fields]
  (let [[type offsets]
        (reduce
          (fn [[type offsets] field]
            (cond

              (not (instance? IFixedCompoundType type))
              (throw (IllegalArgumentException. (str "Invalid field '" field "' for non-compound type " (name type))))
              
              ;; symbol, assumed to be an index
              (not (or (number? field) (keyword? field)))
              (if-not (has-field? type 0)
                (throw (IllegalArgumentException. (str "'" field "' is assumed to be numeric, which isn't accepted by " (name type))))
                [(field-type type field) (conj offsets `(p/* ~(field-offset type 1) ~field))])
              
              ;; keyword or number
              (has-field? type field)
              [(field-type type field) (conj offsets (field-offset type field))]
              
              :else
              (throw (IllegalArgumentException. (str "Invalid field '" field "' for type " (name type))))))
          [type []]
          fields)]
    [type (cons
            (->> offsets (filter number?) (apply +))
            (->> offsets (remove number?)))]))

(defn- field-operation [op-name x fields inlined-form non-inlined-form]
  (let [type (if-let [type (:tag (meta x))]
               @(resolve type)
               (throw (IllegalArgumentException. (str "First argument to " op-name  " must be hinted with element type"))))]

    (let [[inner-type offsets] (inner-type type (rest fields))
          x (with-meta x {})]
      (unify-gensyms
        `(let [x## ~x]
           ~((if (instance? IFixedInlinedType inner-type)
               inlined-form
               non-inlined-form)
             inner-type `x## `(unwrap-byte-seq x##) `(p/+ (index-offset x## ~(first fields)) ~@offsets)))))))

(defmacro get-in*
  "Like `get-in`, but for sequences of typed-structs. The sequence `s` must be type-hinted with the element type,
   which allows for compile-time calculation of the offset, and validation of the lookup."
  [s fields]
  (field-operation "get-in*" s fields
    (fn [type seq byte-seq offset]
      (read-form type byte-seq offset))
    (fn [type seq byte-seq offset]
      `(read-value (element-type ~seq) ~byte-seq ~offset))))

(defmacro set-in!
  "Like `assoc-in`, but for sequences of typed-structs. The sequence `s` must be type-hinted with the element type,
   which allows for compile-time calculation of the offset, and validation of the lookup.

   This is a mutable operation, which writes over values in-place."
  [s fields val]
  (field-operation "set-in!" s fields
    (fn [type seq byte-seq offset]
      `(do
         ~(write-form type byte-seq offset val)
         nil))
    (fn [type seq byte-seq offset]
      `(do
         (write-value (element-type ~seq) ~byte-seq ~offset ~val)
         nil))))

(defmacro update-in!
  "Like `update-in`, but for sequences of typed-structs. The sequence `s` must be type-hints with the element type,
   which allows for compile-time calculation of the offset, and validation of the lookup.

   This is a mutable operation, which writes over values in-place."
  [s fields f & args]
  (field-operation "update-in!" s fields
    (fn [type seq byte-seq offset]
      `(let [offset## ~offset
             byte-seq## ~byte-seq
             val## ~(read-form type `byte-seq## `offset##)
             val'## (~f val## ~@args)]
         ~(write-form type `byte-seq## `offset## `val'##)
         nil))
    (fn [type seq byte-seq offset]
      (let [offset# ~offset
            byte-seq# ~byte-seq
            element-type# (element-type ~seq)
            val# (read-value element-type# byte-seq# offset#)
            val'# (~f val# ~@args)]
        (write-value element-type# byte-seq# offset# val'#)
        nil))))

;;;

(let [;; normal read-write
      s (fn [r w rev]
          `[(partial list '~r)
            (partial list '~w)
            (partial list '~rev)])

      ;; unsigned read-write
      u (fn [r w rev to-unsigned to-signed]
          `[(fn [b# idx#]
              (list '~to-unsigned (list '~r b# idx#)))
            (fn [b# idx# val#]
              (list '~w b# idx# (list '~to-unsigned val#)))
            (fn [x#]
              (list '~to-unsigned (list '~rev (list '~to-signed x#))))])

      types ['int8    1 (s `b/get-int8 `b/put-int8 `identity)
             'uint8   1 (u `b/get-int8 `b/put-int8 `identity `p/int8->uint8 `p/uint8->int8)
             'int16   2 (s `b/get-int16 `b/put-int16 `p/reverse-int16)
             'uint16  2 (u `b/get-int16 `b/put-int16 `p/reverse-int16 `p/int16->uint16 `p/uint16->int16)
             'int32   4 (s `b/get-int32 `b/put-int32 `p/reverse-int32)
             'uint32  4 (u `b/get-int32 `b/put-int32 `p/reverse-int32 `p/int32->uint32 `p/uint32->int32)
             'int64   8 (s `b/get-int64 `b/put-int64 `p/reverse-int64)
             'uint64  8 (u `b/get-int64 `b/put-int64 `p/reverse-int64 `p/int64->uint64 `p/uint64->int64)
             'float32 4 (s `b/get-float32 `b/put-float32 `p/reverse-float32)
             'float64 8 (s `b/get-float64 `b/put-float64 `p/reverse-float64)]]

  (doseq [[name size read-write-rev] (partition 3 types)]
    (let [[read-form write-form rev-form] (eval read-write-rev)]

      (eval
        (unify-gensyms
          `(let [[read-form# write-form# rev-form#] ~read-write-rev]

             ;; basic primitive
             (def ~name
               (reify
                 clojure.lang.Named
                 (getName [_#] ~(str name))
                 (getNamespace [_#] )

                 IFixedType
                 (byte-size [_#]
                   ~size)
                 (write-value [_# byte-seq## offset## x##]
                   ~(write-form `byte-seq## `offset## `x##))
                 (read-value [_# byte-seq## offset##]
                   ~(read-form `byte-seq## `offset##))

                 IFixedInlinedType
                 (read-form [_# b# idx#]
                   (read-form# b# idx#))
                 (write-form [_# b# idx# x#]
                   (write-form# b# idx# x#)))))))

      ;; big and little-endian primitives
      (when-not (#{'int8 'uint8} name)
        (doseq [[check name] (map list
                               [`b/big-endian? `b/little-endian?]
                               [(symbol (str name "-le")) (symbol (str name "-be"))])]

          (eval
            (unify-gensyms
              `(let [[read-form# write-form# rev-form#] ~read-write-rev]
                 (def ~name
                   (reify
                     clojure.lang.Named
                     (getName [_#] ~(str name))
                     (getNamespace [_#] ~(str *ns*))
                     
                     IFixedType
                     (byte-size [_#]
                       ~size)
                     (write-value [_# byte-seq## offset## x##]
                       (let [x## (if (~check byte-seq##)
                                   ~(rev-form `x##)
                                   x##)]
                         ~(write-form `byte-seq## `offset## `x##)))
                     (read-value [_# byte-seq## offset##]
                       (let [x## ~(read-form `byte-seq## `offset##)]
                         (if (~check byte-seq##)
                           ~(rev-form `x##)
                           x##)))

                     IFixedInlinedType
                     (read-form [_# b# idx#]
                       (list 'let [`x## (read-form# b# idx#)]
                         (list 'if (list ~check b#)
                           (rev-form# `x##)
                           `x##)))
                     (write-form [_# b# idx# x#]
                       (list 'let [`x## (list 'if (list ~check b#)
                                          (list rev-form# x#)
                                          x#)]
                         (write-form# b# idx# `x##)))))))))))))

;;;

(def ^:dynamic *types*)

(defn typed-struct
  "A data structure with explicit types, meant to sit atop a byte-seq.  Fields must be keys, and types must
   implement IFixedType.  For better error messages, all structs must be named.

   (typed-struct 'vec2 :x float32 :y float32)

   The resulting value implements IFixedType, and can be used within other typed-structs."
  [name & field+types]

  (assert (even? (count field+types)))
  
  (let [fields (->> field+types (partition 2) (map first))
        types (->> field+types (partition 2) (map second))]

    (assert (every? keyword? fields))

    (doseq [[field type] (map list fields types)]
      (when-not (instance? IFixedType type)
        (throw (IllegalArgumentException. (str field " is not a valid type.")))))
    
    (let [offsets (->> types (map byte-size) (cons 0) (reductions +) butlast)
          byte-size (->> types (map byte-size) (apply +))
          type-syms (map #(symbol (str "t" %)) (range (count types)))]

      (binding [*types* types]
        (eval
          (unify-gensyms
            `(let [~@(interleave type-syms
                       (map
                         (fn [x] `(nth *types* ~x))
                         (range (count types))))]
               (reify
                 clojure.lang.Named
                 (getName [_#] ~(str name))
                 (getNamespace [_#] ~(str *ns*))

                 IFixedCompoundType
                 (has-field? [_# x#]
                   (boolean (~(set fields) x#)))
                 (field-offset [_# k#]
                   (long
                     (case k#
                       ~@(interleave fields offsets))))
                 (field-type [_# k#]
                   (case k#
                     ~@(interleave
                         fields
                         type-syms)))
                 
                 IFixedType
                 (byte-size [_#]
                   ~byte-size)
                 (write-value [_# byte-seq## offset## x##]
                   ~@(map
                       (fn [k offset x]
                         `(write-value ~x byte-seq## (p/+ (long offset##) ~offset) (get x## ~k)))
                       fields
                       offsets
                       type-syms))
                 (read-value [_# byte-seq# offset##]
                   (let [byte-seq## (b/slice byte-seq# offset## ~byte-size)]

                     ;; map structure that sits atop a slice of the byte-seq
                     (reify-map-type
                       (~'keys [_#]
                         ~(vec fields))
                       (~'get [_# k# default-value#]
                         (case k#
                           ~@(interleave
                               fields
                               (map
                                 (fn [x offset]
                                   `(read-value ~x byte-seq## ~offset))
                                 type-syms
                                 offsets))
                           default-value#))
                       (~'assoc [this# k# v#]
                         (assoc (into {} this#) k# v#))
                       (~'dissoc [this# k#]
                         (dissoc (into {} this#) k#)))))))))))))

(defmacro def-typed-struct
  "Like `typed-struct`, but defines a var."
  [name & field+types]
  `(def ~name (typed-struct '~name ~@field+types)))

;;;

(defn- byte-seq-wrapper-reduce
  ([byte-seq-wrapper f start]
     (proto/internal-reduce byte-seq-wrapper f start))
  ([byte-seq-wrapper f]
     (if (nil? byte-seq-wrapper)
       (f)
       (proto/internal-reduce (next byte-seq-wrapper) f (first byte-seq-wrapper)))))

;; a type that, given an IFixedType, can treat a byte-seq as a sequence of that type
;; `stride` and `offset` are so that an inner type be iterated over without copying
(deftype ByteSeqWrapper
  [^long stride
   ^long offset
   ^vertigo.structs.IFixedType type
   ^vertigo.bytes.IByteSeq byte-seq]

  java.io.Closeable
  (close [_]
    (.close ^java.io.Closeable byte-seq))
  
  IByteSeqWrapper
  (unwrap-byte-seq [_] byte-seq)
  (element-type [_] type)
  (index-offset [_ idx] (p/+ offset (p/* idx stride)))

  clojure.lang.ISeq
  clojure.lang.Seqable
  clojure.lang.Sequential
  clojure.lang.Indexed
  clojure.lang.ILookup
  
  (first [_]
    (read-value type byte-seq offset))
  (next [_]
    (when-let [byte-seq' (b/drop-bytes byte-seq stride)]
      (ByteSeqWrapper. stride offset type byte-seq')))
  (more [this]
    (or (next this) '()))
  (count [_]
    (p/div (b/byte-count byte-seq) stride))
  (nth [_ idx]
    (read-value type byte-seq (p/+ offset (p/* stride idx))))
  (nth [this idx default-value]
    (try
      (nth this idx)
      (catch IndexOutOfBoundsException e
        default-value)))
  (valAt [this idx]
    (nth this idx))
  (valAt [this idx not-found]
    (nth this idx not-found))
  (seq [this]
    this)
  (equiv [this x]
    (if-not (sequential? x)
      false
      (loop [a this, b (seq x)]
        (if (or (empty? a) (empty? b))
          (and (empty? a) (empty? b))
          (if (= (first a) (first b))
            (recur (rest a) (rest b))
            false)))))

  proto/InternalReduce

  (internal-reduce [_ f start]
    (b/byte-seq-reduce byte-seq stride
      (fn [byte-seq idx]
        (read-value type byte-seq (p/+ offset (long idx))))
      f
      start))
  
  proto/CollReduce
  (coll-reduce [this f start]
    (byte-seq-wrapper-reduce this f start))
  
  (coll-reduce [this f]
    (byte-seq-wrapper-reduce this f)))

(defn wrap-byte-seq
  "Treats the byte-seq as a sequence of the given type."
  ([type byte-seq]
     (wrap-byte-seq type (byte-size type) 0 byte-seq))
  ([type stride offset byte-seq]
     (ByteSeqWrapper. stride offset type byte-seq)))

(defn marshal-seq
  "Converts a sequence into a marshalled version of itself."
  ([type s]
     (marshal-seq type true s))
  ([type direct? s]
     (let [cnt (count s)
           stride (byte-size type)
           allocate (if direct? b/direct-buffer b/buffer)
           byte-seq (-> (long cnt) (p/* (long stride)) allocate b/byte-seq)]
       (loop [offset 0, s s]
         (when-not (empty? s)
           (write-value type byte-seq offset (first s))
           (recur (p/+ offset stride) (rest s))))
       (wrap-byte-seq type byte-seq))))

(defn lazily-marshal-seq
  "Lazily converst a sequence into a marshalled version of itself."
  ([type s]
     (lazily-marshal-seq type 4096 false s))
  ([type ^long chunk-byte-size direct? s]
     (let [stride (byte-size type)
           chunk-size (p/div chunk-byte-size stride)
           allocate (if direct? b/direct-buffer b/buffer)
           populate (fn populate [s]
                      (when-not (empty? s)
                        (let [nxt (delay (populate (drop chunk-size s)))
                              byte-seq (-> chunk-byte-size allocate (b/lazy-byte-seq nxt))]
                          (loop [idx 0, offset 0, s s]
                            (if (or (p/== chunk-size idx) (empty? s))
                              (b/slice byte-seq 0 offset)
                              (do
                                (write-value type byte-seq offset (first s))
                                (recur (p/inc idx) (p/+ offset stride) (rest s))))))))]
       (wrap-byte-seq type (populate s)))))

;;;

(deftype FixedTypeArray
  [type
   ^long len
   ^long offset
   ^long stride]

  clojure.lang.Named
  (getName [_] (str (name type) "[" len "]"))
  (getNamespace [_] "")

  IFixedCompoundType
  (has-field? [_ idx]
    (and (number? idx) (<= 0 idx (dec len))))
  (field-type [_ idx]
    type)
  (field-offset [_ idx]
    (p/* (long idx) stride))

  IFixedType
  (byte-size [_]
    (p/* len stride))
  (read-value [_ buf offset']
    (wrap-byte-seq
      type stride offset
      (b/slice buf offset' (p/* stride len))))
  (write-value [_ buf offset' x]
    (loop [s x, idx 0]
      (when-not (p/== idx len)
        (write-value type buf (p/+ offset' offset (p/* idx stride)) (first s))
        (recur (rest s) (p/inc idx))))))

(defn array
  "Returns a type representing an array of `type` with length `len`."
  [type ^long len]
  (FixedTypeArray. type len 0 (byte-size type)))

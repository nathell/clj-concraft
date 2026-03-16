(ns concraft.dag
  "DAG data structure mirroring Haskell's Data.DAG from pedestrian-dag.
   DAG = {:node-map {NodeID Node}, :edge-map {EdgeID Edge}}
   Node = {:ingo #{EdgeID}, :outgo #{EdgeID}, :label a}
   Edge = {:tail NodeID, :head NodeID, :label b}")

(defn edge-label
  "Get the label of an edge."
  [dag edge-id]
  (get-in dag [:edge-map edge-id :label]))

(defn set-edge-label
  "Set the label of an edge."
  [dag edge-id label]
  (assoc-in dag [:edge-map edge-id :label] label))

(defn dag-edges
  "List all edge IDs in ascending order."
  [dag]
  (sort (keys (:edge-map dag))))

(defn begins-with
  "Get the tail node ID of an edge."
  [dag edge-id]
  (get-in dag [:edge-map edge-id :tail]))

(defn ends-with
  "Get the head node ID of an edge."
  [dag edge-id]
  (get-in dag [:edge-map edge-id :head]))

(defn prev-edges
  "Get edges incoming to the tail node of the given edge."
  [dag edge-id]
  (let [tail-node (begins-with dag edge-id)]
    (sort (get-in dag [:node-map tail-node :ingo]))))

(defn next-edges
  "Get edges outgoing from the head node of the given edge."
  [dag edge-id]
  (let [head-node (ends-with dag edge-id)]
    (sort (get-in dag [:node-map head-node :outgo]))))

(defn initial-edge?
  "Is this edge at the start of the DAG (no predecessors)?"
  [dag edge-id]
  (empty? (prev-edges dag edge-id)))

(defn final-edge?
  "Is this edge at the end of the DAG (no successors)?"
  [dag edge-id]
  (empty? (next-edges dag edge-id)))

(defn min-edge [dag] (first (dag-edges dag)))
(defn max-edge [dag] (last (dag-edges dag)))

(defn map-e
  "Map a function over edge labels. f takes (edge-id, old-label) → new-label."
  [f dag]
  (reduce (fn [d eid]
            (let [old-label (edge-label d eid)]
              (set-edge-label d eid (f eid old-label))))
          dag
          (dag-edges dag)))

(defn zip-e
  "Zip two DAGs by pairing their edge labels."
  [dag1 dag2]
  (map-e (fn [eid label1]
           [label1 (edge-label dag2 eid)])
         dag1))

(defn fmap
  "Map f over all edge labels."
  [f dag]
  (map-e (fn [_eid label] (f label)) dag))

;; -- DAG construction --

(defn from-edges
  "Build a DAG from a sequence of {:tail NodeID, :head NodeID, :label b}.
   Assigns sequential edge IDs starting from 0.
   Node labels are all nil."
  [edges]
  (let [edge-map (into (sorted-map)
                       (map-indexed (fn [i e] [i e]) edges))
        ;; Collect all node IDs
        node-ids (into (sorted-set)
                       (mapcat (fn [[_ e]] [(:tail e) (:head e)]))
                       edge-map)
        ;; Build node map with ingo/outgo sets
        node-map (reduce (fn [nm [eid {:keys [tail head]}]]
                           (-> nm
                               (update-in [tail :outgo] (fnil conj #{}) eid)
                               (update-in [head :ingo] (fnil conj #{}) eid)))
                         (into (sorted-map)
                               (map (fn [nid] [nid {:ingo #{} :outgo #{} :label nil}]))
                               node-ids)
                         edge-map)]
    {:node-map node-map
     :edge-map edge-map}))

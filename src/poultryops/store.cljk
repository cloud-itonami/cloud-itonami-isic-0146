(ns poultryops.store
  "Store abstraction for poultry-farm facility/flock records. Current
  implementation is an in-memory map; production should migrate to
  Datomic/kotoba-server (the same seam point all cloud-itonami actors
  use). Mirrors `swineops.store` (cloud-itonami-isic-0145) in shape.

  A registered facility is the minimal unit of authority: a poultry
  operation's barn/house complex must be registered before ANY proposal
  referencing it can be considered by the Governor (see
  `poultryops.governor`'s `facility-registered` invariant). Facility data
  is opaque to this namespace -- callers/backends decide what a facility
  record contains (name, barn layout, breed roster, broiler/layer type,
  etc); this Store only answers \"is this facility-id registered, and if
  so what's on file\".")

;; Protocol for swappable store implementations
(defprotocol Store
  (registered-facility [store facility-id]
    "Retrieve a registered facility record by ID. Returns nil if the
    facility-id is nil or not registered."))

;; In-memory implementation (MemStore) for development/testing
(defrecord MemStore [facilities]
  Store
  (registered-facility [_store facility-id]
    (when facility-id
      (get @facilities facility-id))))

(defn mem-store
  "Create an in-memory store. `initial-facilities` is an optional map of
  facility-id -> facility-record."
  [& [{:keys [initial-facilities] :or {initial-facilities {}}}]]
  (MemStore. (atom initial-facilities)))

(defn add-facility
  "Register or update a facility in the store. Used by tests and
  simulation."
  [^MemStore store facility-id facility-data]
  (swap! (:facilities store) assoc facility-id facility-data)
  facility-data)

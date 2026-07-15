(ns poultryops.sim
  "Simple simulation/demo runner for the Poultry-Farm Operations
  Coordinator actor. Used to validate that the actor flow compiles and
  basic proposal flow works. Mirrors `swineops.sim`
  (cloud-itonami-isic-0145)."
  (:require [poultryops.operation :as operation]
            [poultryops.store :as store]))

(defn demo
  "Run a simple demo scenario: register a facility, propose a flock-record
  log, and check the disposition flow."
  []
  (let [;; Create store with a registered facility
        st (store/mem-store
            {:initial-facilities
             {"farm-001"
              {:id "farm-001"
               :name "Sunrise Poultry Farm"
               :barn "House 4"
               :breed "ross308"}}})

        ;; Build actor
        actor (operation/build st)

        ;; Create a request to log a flock record
        request {:op :log-flock-record
                 :facility-id "farm-001"
                 :count 8000
                 :health-status "healthy"}

        ;; Context with phase 0 (simulation)
        context {:actor-id "poultry-ops-01"
                 :role :farm-operator
                 :phase :phase-0}]

    (println "=== Poultry-Farm Operations Coordinator Demo ===")
    (println "Demo facility: farm-001 (House 4)")
    (println "Request: log-flock-record")
    (println "Phase: phase-0 (simulation)")
    (println "Expected: escalate (phase-0 forces human review of all commits)")
    (println)
    (let [result (actor request context)]
      (println "Result disposition:" (:disposition result))
      result)))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
)

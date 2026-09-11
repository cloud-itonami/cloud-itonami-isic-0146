(ns poultryops.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [poultryops.store :as store]))

(deftest mem-store-creation
  (testing "Create empty store"
    (let [st (store/mem-store)]
      (is (some? st))
      (is (satisfies? store/Store st))))

  (testing "Create store with initial facilities"
    (let [facilities {"farm-001" {:id "farm-001" :name "Sunrise Poultry Farm"}}
          st (store/mem-store {:initial-facilities facilities})]
      (is (some? st))
      (is (satisfies? store/Store st)))))

(deftest registered-facility-retrieval
  (testing "Retrieve existing facility"
    (let [facility {:id "farm-001" :name "Sunrise Poultry Farm"}
          st (store/mem-store {:initial-facilities {"farm-001" facility}})]
      (is (= facility (store/registered-facility st "farm-001")))))

  (testing "Retrieve non-existent facility"
    (let [st (store/mem-store)]
      (is (nil? (store/registered-facility st "no-such-farm")))))

  (testing "nil facility-id returns nil (never falls through to a default)"
    (let [st (store/mem-store {:initial-facilities {"farm-001" {:id "farm-001"}}})]
      (is (nil? (store/registered-facility st nil))))))

(deftest add-facility-test
  (testing "Register a new facility"
    (let [st (store/mem-store)
          facility-data {:id "farm-002" :name "New Poultry Farm"}
          result (store/add-facility st "farm-002" facility-data)]
      (is (= facility-data result))
      (is (= facility-data (store/registered-facility st "farm-002")))))

  (testing "Update an existing facility"
    (let [st (store/mem-store {:initial-facilities {"farm-001" {:id "farm-001"}}})
          updated {:id "farm-001" :name "Renamed Poultry Farm"}
          result (store/add-facility st "farm-001" updated)]
      (is (= updated result))
      (is (= updated (store/registered-facility st "farm-001"))))))

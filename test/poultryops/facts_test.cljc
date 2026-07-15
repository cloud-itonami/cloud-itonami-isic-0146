(ns poultryops.facts-test
  (:require [clojure.test :refer [deftest is are testing]]
            [poultryops.facts :as facts]))

(deftest supply-category-lookup
  (testing "Lookup valid supply category"
    (let [c (facts/supply-category-by-id "feed")]
      (is (= "feed" (:id c)))
      (is (= "飼料" (:name c)))))

  (testing "Lookup invalid supply category"
    (is (nil? (facts/supply-category-by-id "unknown")))))

(deftest supply-category-cost-thresholds
  (testing "Category-specific cost thresholds"
    (are [id expected] (= expected (:cost-threshold (facts/supply-category-by-id id)))
      "feed"                   500
      "veterinary-supply"      500
      "biosecurity-equipment"  1000)))

(deftest default-cost-threshold-value
  (testing "Default fallback threshold matches the conservative baseline"
    (is (= 500 facts/default-cost-threshold))))

(deftest breed-lookup
  (testing "Lookup valid breed"
    (are [id expected-name] (= expected-name (:name (facts/breed-by-id id)))
      "cobb500"   "コブ500 (Cobb 500)"
      "ross308"   "ロス308 (Ross 308)"
      "leghorn"   "白色レグホーン (White Leghorn)"
      "isa-brown" "ISAブラウン (ISA Brown)"))

  (testing "Lookup invalid breed"
    (is (nil? (facts/breed-by-id "unknown")))))

(deftest breed-flock-type-lookup
  (testing "Broiler breeds are tagged :broiler"
    (are [id] (= :broiler (:flock-type (facts/breed-by-id id)))
      "cobb500"
      "ross308"))

  (testing "Layer breeds are tagged :layer"
    (are [id] (= :layer (:flock-type (facts/breed-by-id id)))
      "leghorn"
      "isa-brown")))

(deftest biosecurity-concern-lookup
  (testing "Lookup valid biosecurity/notifiable-disease concern"
    (let [c (facts/biosecurity-concern-by-id "hpai")]
      (is (= "hpai" (:id c)))
      (is (true? (:notifiable c)))))

  (testing "Notifiable flag distinguishes reportable diseases"
    (are [id expected-notifiable?] (= expected-notifiable? (:notifiable (facts/biosecurity-concern-by-id id)))
      "hpai" true
      "nd"   true
      "ib"   false
      "ibd"  false))

  (testing "Lookup invalid concern"
    (is (nil? (facts/biosecurity-concern-by-id "unknown")))))

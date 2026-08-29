(ns igata.methods.test-magnesium-hpdc
  "Focused tests for the magnesium-HPDC cell decision contract (activity ->
  decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-hpdc :as mg]))

(def ^:private valid-req
  {:activity/id "act-mg-0001"
   :alloy {:family "AZ91D" :composition-disclosed? true}
   :measured-melt-temp-c 615
   :cover-gas {:agent "Novec-7100-inert-class" :measured-flow-lmin 12}
   :interlocks [:dry-dust-collection :class-d-extinguisher :no-water-contact :machine-guard-verified]
   :clamping-force-tons 350
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete" "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-08-30T00:00:00Z"
                    :scope #{:melt :shot}}
   :requested-effect :simulate-plan})

(deftest test-happy-path-approves-simulate-only
  (let [r (mg/plan-melt-and-shot valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-machine-command-refused-unconditionally
  (let [r (mg/plan-melt-and-shot (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-undisclosed-composition-refused-g2
  (let [r (mg/plan-melt-and-shot (assoc-in valid-req [:alloy :composition-disclosed?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G2"))))

(deftest test-unmeasured-melt-temp-refused-not-invented
  (let [r (mg/plan-melt-and-shot (dissoc valid-req :measured-melt-temp-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes"))))

(deftest test-missing-cover-gas-refused
  (let [r (mg/plan-melt-and-shot (dissoc valid-req :cover-gas))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "cover gas"))))

(deftest test-water-based-atmosphere-refused
  (let [r (mg/plan-melt-and-shot (assoc-in valid-req [:cover-gas :agent] "water-mist"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "water"))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (mg/plan-melt-and-shot (assoc valid-req :interlocks [:dry-dust-collection]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "class-d-extinguisher"))))

(deftest test-clamping-over-g1-refused
  (let [r (mg/plan-melt-and-shot (assoc valid-req :clamping-force-tons 6500))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G1"))))

(deftest test-witness-quorum-g4-refused
  (let [r (mg/plan-melt-and-shot (assoc valid-req :witness-robot-dids ["did:one"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G4"))))

(deftest test-missing-human-approval-defers-never-approves
  (let [r (mg/plan-melt-and-shot (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval")))
  (let [r (mg/plan-melt-and-shot (assoc-in valid-req [:human-approval :scope] #{:melt}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))))

(deftest test-unknown-alloy-family-refused
  (let [r (mg/plan-melt-and-shot (assoc-in valid-req [:alloy :family] "A380"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G2-magnesium"))))

(deftest test-screening-always-defers-procurement-to-human
  (let [offer {:activity/id "act-eq-0001"
               :manufacturer "Example Magnesium Machine Works"
               :model "MG-HPDC-650"
               :equipment-class :magnesium-hpdc-machine
               :condition "used"
               :seller "owner-operated dealer"
               :source-url "https://dealer.example.com/inventory/mg-hpdc-650"
               :observed-at "2026-08-30"}
        r (mg/screen-equipment-offer offer)]
    (is (= :deferred (:decision r)))
    (is (= :deferred-human-approval (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (= "used" (get-in r [:effect :effect/screening :condition])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "human"))))

(deftest test-screening-unknown-condition-is-representable
  (let [r (mg/screen-equipment-offer {:activity/id "act-eq-0002"
                                      :condition "unknown"
                                      :source-url "https://mfg.example.com/offer/x"})]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-screening-unmeasured-fields-recorded-not-invented
  (let [r (mg/screen-equipment-offer {:activity/id "act-eq-0003"
                                      :condition "refurbished"
                                      :source-url "https://mfg.example.com/offer/y"
                                      :lead-time "8-weeks-observed"})]
    (is (contains? (get-in r [:effect :effect/screening :unmeasured]) :lead-time))
    (is (contains? (set (get-in r [:effect :effect/screening :unmeasured-fields])) :price))
    (is (nil? (get-in r [:effect :effect/screening :unmeasured :price]))
        "no price value may be invented")))

(deftest test-screening-rejects-unrecorded-condition
  (let [r (mg/screen-equipment-offer {:activity/id "act-eq-0004"
                                      :condition "looks-fine"
                                      :source-url "https://mfg.example.com/offer/z"})]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-decision-is-deterministic
  (is (= (mg/plan-melt-and-shot valid-req) (mg/plan-melt-and-shot valid-req))))

(deftest test-all-refusals-carry-audit-tail
  (doseq [r [(mg/plan-melt-and-shot (dissoc valid-req :activity/id))
             (mg/plan-melt-and-shot (assoc valid-req :clamping-force-tons 9999))
             (mg/screen-equipment-offer {:activity/id "x" :condition "new"})]]
    (is (contains? r :audit))
    (is (contains? (:audit r) :audit/gates-checked))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

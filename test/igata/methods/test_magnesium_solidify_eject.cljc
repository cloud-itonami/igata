(ns igata.methods.test-magnesium-solidify-eject
  "Focused tests for the magnesium solidification/eject cell decision contract
  (activity -> decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [igata.methods.magnesium-solidify-eject :as mse]))

(def ^:private valid-req
  {:activity/id "act-mg-se-0001"
   :cast-shot-record-id "shot-mg-0001"
   :alloy {:family "AZ91D" :composition-disclosed? true}
   :measured-die-temp-c 210
   :measured-part-eject-temp-c 180
   :cooling {:medium "inert-gas-convection" :contact-with-part true}
   :interlocks [:dry-dust-collection :no-water-contact
                :machine-guard-verified :hot-part-handling-verified]
   :ejector-stroke-mm 45.5
   :ejection-force-kgf 320.0
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:solidify :eject}}
   :requested-effect :simulate-plan})

;; ── plan-solidify-and-eject ────────────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (mse/plan-solidify-and-eject valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r)))
                   :human-approval-approved))))

(deftest test-ejector-command-refused-unconditionally
  (let [r (mse/plan-solidify-and-eject
           (assoc valid-req :requested-effect :command-ejector))]
    (is (= :refused (:decision r)))
    (is (= :none (get-in r [:effect :effect/kind])))
    (is (contains? (set (:audit/gates-checked (:audit r)))
                   :no-physical-command))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "no-physical-command"))))

(deftest test-water-cooling-contact-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc-in valid-req [:cooling :medium] "water-mist"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "water or aqueous medium"))))

(deftest test-water-medium-not-touching-part-allowed
  (let [r (mse/plan-solidify-and-eject
           (assoc valid-req :cooling {:medium "water-mist"
                                      :contact-with-part false}))]
    (is (= :approved (:decision r))
        "water cooling that provably does not touch the hot Mg part is admissible")))

(deftest test-missing-upstream-shot-link-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc valid-req :cast-shot-record-id ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "must not fork"))))

(deftest test-unmeasured-die-temp-refused
  (let [r (mse/plan-solidify-and-eject
           (dissoc valid-req :measured-die-temp-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "never substitutes a literature"))))

(deftest test-unmeasured-ejector-settings-refused
  (let [r (mse/plan-solidify-and-eject (dissoc valid-req :ejection-force-kgf))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "never invents"))))

(deftest test-interlock-gap-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc valid-req :interlocks
                  [:dry-dust-collection :no-water-contact
                   :machine-guard-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       ":hot-part-handling-verified"))))

(deftest test-g4-quorum-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc valid-req :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G4"))))

(deftest test-partial-human-approval-scope-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc-in valid-req [:human-approval :scope] #{:solidify}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal])
                       "absence defers, never approves"))))

(deftest test-non-magnesium-alloy-refused
  (let [r (mse/plan-solidify-and-eject
           (assoc-in valid-req [:alloy :family] "A380"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G2-magnesium"))))

;; ── record-ejected-part-handoff ────────────────────────────────────────────

(def ^:private valid-handoff
  {:activity/id "act-mg-ho-0001"
   :cast-shot-record-id "shot-mg-0001"
   :ejected-part-record-id "eject-mg-0001"
   :alloy-family "AZ91D"
   :shot-alloy-family "AZ91D"
   :measured-part-eject-temp-c 180
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]})

(deftest test-handoff-approves-and-closes-chain
  (let [r (mse/record-ejected-part-handoff valid-handoff)]
    (is (= :approved (:decision r)))
    (is (= :traceability-record (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r)))
                   :traceability-chain-intact))
    (is (= "shot-mg-0001" (get-in r [:effect :effect/record :cast-shot-record-id])))
    (is (= "eject-mg-0001" (get-in r [:effect :effect/record :ejected-part-record-id])))))

(deftest test-handoff-alloy-continuity-refused
  (let [r (mse/record-ejected-part-handoff
           (assoc valid-handoff :shot-alloy-family "AM60"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G2-continuity"))))

(deftest test-handoff-missing-upstream-refused
  (let [r (mse/record-ejected-part-handoff
           (dissoc valid-handoff :cast-shot-record-id))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "upstream MES link"))))

(deftest test-handoff-unmeasured-temp-refused
  (let [r (mse/record-ejected-part-handoff
           (dissoc valid-handoff :measured-part-eject-temp-c))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "never assumed"))))

(deftest test-handoff-g4-quorum-refused
  (let [r (mse/record-ejected-part-handoff
           (assoc valid-handoff :witness-robot-dids []))]
    (is (= :refused (:decision r)))
    (is (str/includes? (get-in r [:audit :audit/refusal]) "G4"))))

(deftest test-determinism
  (is (= (mse/plan-solidify-and-eject valid-req)
         (mse/plan-solidify-and-eject valid-req)))
  (is (= (mse/record-ejected-part-handoff valid-handoff)
         (mse/record-ejected-part-handoff valid-handoff))))

(defn run-all []
  (let [{:keys [fail error]} (run-tests)]
    (when (pos? (+ fail error))
      (System/exit 1))))

(run-all)

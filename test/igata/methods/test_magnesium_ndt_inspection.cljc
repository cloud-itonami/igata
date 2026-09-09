(ns igata.methods.test-magnesium-ndt-inspection
  "Focused tests for the NDT inspection decision contract slice (activity ->
  decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [igata.methods.magnesium-ndt-inspection :as ndt]))

(def ^:private valid-req
  {:activity/id "act-mg-ndt-0001"
   :part/id "part-mg-0001"
   :lineage-cids {:alloy "bafy-alloy-cid" :die "bafy-die-cid"
                  :shot "bafy-shot-cid" :qc "bafy-qc-cid"}
   :operator-did "did:web:etzhayyim.com:person:operator-1"
   :measured/max-pore-area-pct 0.8
   :measured/max-dimensional-deviation-mm 0.05
   :acceptance/max-pore-area-pct 1.0
   :acceptance/max-deviation-mm 0.1
   :interlocks [:ct-bay-interlock-verified :radiation-exposure-badge-worn
                :part-fixtured-secured]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:mimi" "did:web:etzhayyim.com:igata:simeon"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-04T00:00:00Z"
                    :scope #{:operate-xray-ct-bay}}
   :requested-effect :simulate-plan})

;; ── activity 1: NDT inspection plan ────────────────────────────────────────

(deftest test-happy-path-approves-simulate-only
  (let [r (ndt/plan-ndt-inspection valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (= :pass (get-in r [:effect :effect/plan :xray-verdict])))
    (is (= :pass (get-in r [:effect :effect/plan :cmm-verdict])))
    (is (false? (get-in r [:effect :effect/plan :acceptance-bounds-invented])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-measured-over-bound-fails-verdict-but-approves-record
  "A failing measurement is a valid measured outcome: the record approves,
  the verdict says :fail — the module never hides a failing part."
  (let [r (ndt/plan-ndt-inspection (assoc valid-req
                                          :measured/max-pore-area-pct 2.5
                                          :measured/max-dimensional-deviation-mm 0.3))]
    (is (= :approved (:decision r)))
    (is (= :fail (get-in r [:effect :effect/plan :xray-verdict])))
    (is (= :fail (get-in r [:effect :effect/plan :cmm-verdict])))))

(deftest test-missing-measured-reading-stays-unmeasured-never-invented
  (let [r (ndt/plan-ndt-inspection (assoc valid-req
                                          :measured/max-pore-area-pct nil))]
    (is (= :approved (:decision r)))
    (is (= :unmeasured (get-in r [:effect :effect/plan :xray-verdict])))
    (is (= :pass (get-in r [:effect :effect/plan :cmm-verdict])))))

(deftest test-negative-measured-reading-refused-not-normalized
  (let [r (ndt/plan-ndt-inspection (assoc valid-req
                                          :measured/max-pore-area-pct -0.5))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes"))))

(deftest test-missing-acceptance-bounds-refused-not-invented
  (let [r (ndt/plan-ndt-inspection (dissoc valid-req :acceptance/max-pore-area-pct))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never invents"))))

(deftest test-machine-command-refused-unconditionally
  (let [r (ndt/plan-ndt-inspection (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-missing-lineage-cid-refused-g14
  (let [r (ndt/plan-ndt-inspection (assoc-in valid-req [:lineage-cids :shot] ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G14"))
    (is (str/includes? (:audit/refusal (:audit r)) ":shot"))))

(deftest test-missing-operator-did-refused-g11
  (let [r (ndt/plan-ndt-inspection (dissoc valid-req :operator-did))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G11"))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (ndt/plan-ndt-inspection (assoc valid-req :interlocks [:part-fixtured-secured]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "ct-bay-interlock-verified"))))

(deftest test-radiation-badge-interlock-cannot-be-omitted
  (let [r (ndt/plan-ndt-inspection (assoc valid-req
                                          :interlocks [:ct-bay-interlock-verified
                                                       :part-fixtured-secured]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "radiation-exposure-badge-worn"))))

(deftest test-missing-witness-quorum-refused
  (let [r (ndt/plan-ndt-inspection (assoc valid-req :witness-robot-dids ["did:web:etzhayyim.com:igata:mimi"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G4"))))

(deftest test-missing-human-approval-defers-never-approves
  (let [r (ndt/plan-ndt-inspection (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))
    (is (str/includes? (:audit/refusal (:audit r)) "never approves"))))

(deftest test-wrong-scope-human-approval-refused
  (let [r (ndt/plan-ndt-inspection (assoc-in valid-req [:human-approval :scope] #{:trim}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) ":operate-xray-ct-bay"))))

(deftest test-disposition-not-decided-here
  "The module judges verdicts from measurement, but the final
  :accept/:rework/:scrap disposition stays with the caller's QC evidence —
  the effect explicitly says so."
  (let [r (ndt/plan-ndt-inspection valid-req)]
    (is (false? (get-in r [:effect :effect/plan :disposition-decided-here])))
    (is (nil? (get-in r [:effect :effect/plan :qc-disposition])))))

;; ── activity 2: NDT equipment-offer screening ──────────────────────────────

(def ^:private valid-offer
  {:activity/id "act-mg-ndt-eq-0001"
   :manufacturer "Example Industrial CT Co."
   :model "CT-225"
   :equipment-class :xray-ct-and-cmm
   :condition "used"
   :seller "Example Industrial CT Co. (first party)"
   :source-url "https://example.co.jp/ct/CT-225"
   :observed-at "2026-09-04T00:00:00Z"})

(deftest test-ndt-offer-deferred-to-human
  (let [r (ndt/screen-ndt-equipment-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))
    (is (= "used" (get-in r [:effect :effect/screening :condition])))))

(deftest test-ndt-offer-unknown-condition-recorded-not-normalized
  (let [r (ndt/screen-ndt-equipment-offer (assoc valid-offer :condition "unknown"))]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-ndt-offer-unrecognized-condition-refused
  (let [r (ndt/screen-ndt-equipment-offer (assoc valid-offer :condition "as-is"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-ndt-offer-aggregator-source-refused
  (let [r (ndt/screen-ndt-equipment-offer (assoc valid-offer :source-url ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "source"))))

(deftest test-ndt-offer-wrong-equipment-class-refused
  (let [r (ndt/screen-ndt-equipment-offer (assoc valid-offer :equipment-class :melting-and-dosing-furnace))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "equipment-class"))))

(ns igata.methods.test-magnesium-trim-qc
  "Focused tests for the trim-and-QC decision contract slice (activity ->
  decision -> effect -> audit). Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [igata.methods.magnesium-trim-qc :as tq]))

(def ^:private valid-req
  {:activity/id "act-mg-trim-0001"
   :part/id "part-mg-0001"
   :lineage-cids {:alloy "bafy-alloy-cid" :die "bafy-die-cid"
                  :shot "bafy-shot-cid" :qc "bafy-qc-cid"}
   :measured/total-input-g 1200.0
   :measured/recovered-g 1150.0
   :qc-disposition :accept
   :interlocks [:dry-chip-collection :class-d-extinguisher :no-water-contact :machine-guard-verified]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:mimi" "did:web:etzhayyim.com:igata:simeon"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-01T00:00:00Z"
                    :scope #{:trim}}
   :requested-effect :simulate-plan})

(deftest test-happy-path-approves-simulate-only
  (let [r (tq/plan-trim-and-qc valid-req)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    ;; recovery computed from measured masses, not asserted
    (is (= 0.9583333333333334 (get-in r [:effect :effect/plan :computed-recovery-ratio])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-machine-command-refused-unconditionally
  (let [r (tq/plan-trim-and-qc (assoc valid-req :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-missing-lineage-cid-refused-g14
  (let [r (tq/plan-trim-and-qc (assoc-in valid-req [:lineage-cids :shot] ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G14"))
    (is (str/includes? (:audit/refusal (:audit r)) ":shot"))))

(deftest test-unmeasured-masses-refused-not-invented
  (let [r (tq/plan-trim-and-qc (dissoc valid-req :measured/recovered-g))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))
    (is (str/includes? (:audit/refusal (:audit r)) "never substitutes"))))

(deftest test-recovery-below-g10-threshold-refused
  (let [r (tq/plan-trim-and-qc (assoc valid-req :measured/recovered-g 1100.0))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G10"))))

(deftest test-zero-total-input-refused-not-divide-by-zero
  (let [r (tq/plan-trim-and-qc (assoc valid-req :measured/total-input-g 0.0))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unmeasured"))))

(deftest test-invalid-qc-disposition-refused
  (let [r (tq/plan-trim-and-qc (assoc valid-req :qc-disposition :auto-passed))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "qc-disposition"))))

(deftest test-bot-does-not-judge-qc
  "The module records the caller's QC disposition without re-judging it —
  :scrap with full evidence must also approve the record, since scrap routing
  is a valid measured outcome."
  (let [r (tq/plan-trim-and-qc (assoc valid-req :qc-disposition :scrap))]
    (is (= :approved (:decision r)))
    (is (= :scrap (get-in r [:effect :effect/plan :qc-disposition])))))

(deftest test-missing-interlocks-refused-with-required-list
  (let [r (tq/plan-trim-and-qc (assoc valid-req :interlocks [:machine-guard-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "dry-chip-collection"))))

(deftest test-water-contact-interlock-cannot-be-omitted
  (let [r (tq/plan-trim-and-qc (assoc valid-req :interlocks [:dry-chip-collection :class-d-extinguisher :machine-guard-verified]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-water-contact"))))

(deftest test-missing-witness-quorum-refused
  (let [r (tq/plan-trim-and-qc (assoc valid-req :witness-robot-dids ["did:web:etzhayyim.com:igata:mimi"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "G4"))))

(deftest test-missing-human-approval-defers-never-approves
  (let [r (tq/plan-trim-and-qc (dissoc valid-req :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "human-approval"))
    (is (str/includes? (:audit/refusal (:audit r)) "never approves"))))

(deftest test-wrong-scope-human-approval-refused
  (let [r (tq/plan-trim-and-qc (assoc-in valid-req [:human-approval :scope] #{:melt}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) ":trim"))))

;; ── procurement screening ───────────────────────────────────────────────────

(def ^:private valid-offer
  {:activity/id "act-mg-scrap-0001"
   :manufacturer "Example Chip Recovery Co."
   :model "CR-100"
   :equipment-class :magnesium-chip-recovery
   :condition "refurbished"
   :seller "Example Chip Recovery Co. (first party)"
   :source-url "https://example.co.jp/chip-recovery/CR-100"
   :observed-at "2026-09-01T00:00:00Z"})

(deftest test-scrap-offer-deferred-to-human
  (let [r (tq/screen-scrap-recovery-offer valid-offer)]
    (is (= :deferred (:decision r)))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :no-financial-commitment))
    (is (= "refurbished" (get-in r [:effect :effect/screening :condition])))))

(deftest test-scrap-offer-unknown-condition-recorded-not-normalized
  (let [r (tq/screen-scrap-recovery-offer (assoc valid-offer :condition "unknown"))]
    (is (= :deferred (:decision r)))
    (is (= "unknown" (get-in r [:effect :effect/screening :condition])))))

(deftest test-scrap-offer-unrecognized-condition-refused
  (let [r (tq/screen-scrap-recovery-offer (assoc valid-offer :condition "as-is"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "condition"))))

(deftest test-scrap-offer-aggregator-source-refused
  (let [r (tq/screen-scrap-recovery-offer (assoc valid-offer :source-url ""))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "source"))))

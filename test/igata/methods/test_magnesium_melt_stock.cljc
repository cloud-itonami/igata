(ns igata.methods.test-magnesium-melt-stock
  "Focused tests for the melt-stock screening & revert routing decision
  contract. Pure; deterministic; stdlib only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [igata.methods.magnesium-melt-stock :as ms]))

(def ^:private valid-screening
  {:activity/id "act-mg-stock-0001"
   :stock {:lot-ref "lot-mg-2026-091"
           :source-class :new-ingot
           :alloy-family "AZ91D"
           :mill-attestation-ref "alloy-attestation-2026-0871"
           :moisture-inspected? true
           :contamination-screen {:screened? true :result :pass}}
   :melt-plan-alloy-family "AZ91D"
   :interlocks [:dry-dust-collection :class-d-extinguisher :no-water-contact]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-03T00:00:00Z"
                    :scope #{:melt-stock}}
   :requested-effect :simulate-plan})

(def ^:private valid-revert-hold
  {:activity/id "act-mg-revert-hold-0001"
   :stock {:lot-ref "lot-mg-2026-091"
           :source-class :trim-revert
           :alloy-family "AZ91D"
           :trim-qc-record-ref "trim-qc-rec-2026-0412"}
   :hold-reason "contamination-screen-fail"
   :hold-location "bay-3-dry-revert-rack"
   :measured-mass-g 840
   :interlocks [:dry-dust-collection :class-d-extinguisher :no-water-contact]
   :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"
                        "did:web:etzhayyim.com:igata:mimi"]
   :human-approval {:approver-did "did:web:etzhayyim.com:person:owner"
                    :approved-at "2026-09-03T00:00:00Z"
                    :scope #{:revert}}
   :requested-effect :simulate-plan})

(defn- dissoc-in [m ks] (update-in m (butlast ks) dissoc (last ks)))

(defn- revert-screening [over]
  (-> valid-screening
      (assoc-in [:stock :source-class] :trim-revert)
      (assoc-in [:stock :trim-qc-record-ref] "trim-qc-rec-2026-0412")
      (dissoc-in [:stock :mill-attestation-ref])
      over))

;; ── screen-melt-stock ──────────────────────────────────────────────────────

(deftest test-screening-happy-path-approves-charge-eligible
  (let [r (ms/screen-melt-stock valid-screening)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (= :charge-eligible (get-in r [:effect :effect/plan :routing])))
    (is (false? (get-in r [:effect :effect/machine-command])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))
    (is (contains? (set (:audit/gates-checked (:audit r))) :human-approval-approved))))

(deftest test-screening-screen-fail-routes-to-revert-hold
  (let [r (ms/screen-melt-stock (assoc-in valid-screening
                                          [:stock :contamination-screen :result] :fail))]
    (is (= :approved (:decision r)))
    (is (= :revert-hold (get-in r [:effect :effect/plan :routing])))))

(deftest test-screening-machine-command-refused-unconditionally
  (let [r (ms/screen-melt-stock (assoc valid-screening :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-screening-unknown-source-class-refused
  (let [r (ms/screen-melt-stock (assoc-in valid-screening [:stock :source-class] :purgatory-scrap))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "unknown or undeclared"))))

(deftest test-screening-dross-refused-outright
  (let [r (ms/screen-melt-stock (assoc-in valid-screening [:stock :source-class] :dross))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "dross"))))

(deftest test-screening-new-ingot-without-attestation-refused
  (let [r (ms/screen-melt-stock (dissoc-in valid-screening [:stock :mill-attestation-ref]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "mill"))))

(deftest test-screening-revert-without-trim-qc-record-refused
  (let [r (ms/screen-melt-stock (revert-screening (fn [m] (dissoc-in m [:stock :trim-qc-record-ref]))))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "trim-QC record"))))

(deftest test-screening-alloy-family-mismatch-refused
  (let [r (ms/screen-melt-stock (assoc valid-screening :melt-plan-alloy-family "AM60B"))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "continuity must hold"))))

(deftest test-screening-missing-alloy-family-declarations-refused
  (let [r1 (ms/screen-melt-stock (assoc-in valid-screening [:stock :alloy-family] nil))
        r2 (ms/screen-melt-stock (dissoc valid-screening :melt-plan-alloy-family))]
    (is (= :refused (:decision r1)))
    (is (= :refused (:decision r2)))))

(deftest test-screening-uninspected-moisture-refused
  (let [r (ms/screen-melt-stock (assoc-in valid-screening [:stock :moisture-inspected?] false))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "steam explosion"))))

(deftest test-screening-unscreenshown-lot-refused
  (let [r (ms/screen-melt-stock (assoc-in valid-screening [:stock :contamination-screen] {:screened? false :result :pass}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "contamination screening"))))

(deftest test-screening-undeclared-screen-result-refused
  (let [r (ms/screen-melt-stock (assoc-in valid-screening [:stock :contamination-screen] {:screened? true :result :unknown}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) ":pass or\n               :fail"))))

(deftest test-screening-interlock-gap-refused
  (let [r (ms/screen-melt-stock (assoc valid-screening :interlocks [:dry-dust-collection :no-water-contact]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "interlocks incomplete"))))

(deftest test-screening-witness-quorum-refused
  (let [r (ms/screen-melt-stock (assoc valid-screening :witness-robot-dids ["did:web:etzhayyim.com:igata:otete"]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "witness quorum"))))

(deftest test-screening-missing-human-approval-defers
  (let [r (ms/screen-melt-stock (dissoc valid-screening :human-approval))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "defers, never approves"))))

;; ── plan-revert-hold ───────────────────────────────────────────────────────

(deftest test-revert-hold-happy-path-approves-simulate-only
  (let [r (ms/plan-revert-hold valid-revert-hold)]
    (is (= :approved (:decision r)))
    (is (= :simulate-plan-only (get-in r [:effect :effect/kind])))
    (is (= 840 (get-in r [:effect :effect/plan :measured-mass-g])))
    (is (false? (get-in r [:audit :audit/bot-commanded-equipment])))))

(deftest test-revert-hold-dross-not-routable
  (let [r (ms/plan-revert-hold (assoc-in valid-revert-hold [:stock :source-class] :dross))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "dross"))))

(deftest test-revert-hold-primary-ingot-not-routable
  (let [r (ms/plan-revert-hold (assoc-in valid-revert-hold [:stock :source-class] :new-ingot))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "revert"))))

(deftest test-revert-hold-machine-command-refused-unconditionally
  (let [r (ms/plan-revert-hold (assoc valid-revert-hold :requested-effect :command-machine))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "no-physical-command"))))

(deftest test-revert-hold-missing-hold-reason-or-location-refused
  (let [r1 (ms/plan-revert-hold (dissoc valid-revert-hold :hold-reason))
        r2 (ms/plan-revert-hold (dissoc valid-revert-hold :hold-location))]
    (is (= :refused (:decision r1)))
    (is (= :refused (:decision r2)))))

(deftest test-revert-hold-invented-or-invalid-mass-refused
  (let [r1 (ms/plan-revert-hold (dissoc valid-revert-hold :measured-mass-g))
        r2 (ms/plan-revert-hold (assoc valid-revert-hold :measured-mass-g 0))]
    (is (= :refused (:decision r1)))
    (is (= :refused (:decision r2)))
    (is (str/includes? (:audit/refusal (:audit r1)) "never invents a lot mass"))))

(deftest test-revert-hold-wrong-approval-scope-defers
  (let [r (ms/plan-revert-hold (assoc-in valid-revert-hold [:human-approval :scope] #{:melt-stock}))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "defers, never approves"))))

(deftest test-revert-hold-missing-trim-qc-record-refused
  (let [r (ms/plan-revert-hold (dissoc-in valid-revert-hold [:stock :trim-qc-record-ref]))]
    (is (= :refused (:decision r)))
    (is (str/includes? (:audit/refusal (:audit r)) "trace chain"))))

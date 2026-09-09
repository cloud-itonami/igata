(ns igata.methods.magnesium-die-prep
  "magnesium_die_prep.cljc — 鋳型 magnesium-HPDC die-preparation decision contract
  (:magnesium-hpdc manufacturing cell, scripts/hermes-magnesium-systems-bots/
  system-scope.edn on com-junkawasaki origin/main).

  Upstream link of the magnesium HPDC cell chain: melt-and-shot is landed
  (magnesium-hpdc, PR #2) and trim/QC is open (magnesium-trim-qc, PR #3); this
  module covers die preparation between them. It models
  activity -> decision -> effect -> audit for preparing a die for a magnesium
  shot. The bot may design and simulate; it may NOT command physical equipment
  — a machine command is refused unconditionally.

  Hazard boundaries encoded (hot die, die-handling crane, atomized release
  agent over a hot cavity):
    - die thermal-cycle life must be decided from MEASURED values supplied by
      the caller (:measured-thermal-cycles and :max-thermal-cycles); this
      module never invents or defaults a die-life constant, and a die at or
      past its recorded life is refused outright
    - a recorded crack inspection (:method, :result, :inspected-at) is required;
      a :fail result refuses the die unconditionally (a cracked die holding
      molten magnesium under shot pressure is a molten-metal ejection hazard)
    - release-agent lot must be declared with a recorded G7 forbidden-substance
      scan result (no OPCW Schedule compounds / closed release agents)
    - interlocks required before approval: machine guard verified, hot-surface
      PPE, die-handling crane verified (a magnesium die is a multi-hundred-kg
      suspended load)
    - human approval is required for both hazardous steps (:die-handling and
      :agent-spray) — absence defers, never approves
    - no capacity, cycle time, yield, price or certification value is invented;
      missing values stay :unmeasured

  Constitutional parity with manifest.edn gates: G4 (witness quorum ≥ 2 robot
  signers), G7 (no forbidden release agent), G14 (lineage CIDs recorded for
  the die attestation chain).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private required-interlocks
  #{:machine-guard-verified :hot-surface-ppe :die-crane-verified})
(def ^:private human-approval-steps #{:die-handling :agent-spray})
(def ^:private recognized-crack-results #{:pass :fail})

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- present? [x]
  (cond (string? x) (not (str/blank? x))
        (nil? x) false
        :else true))

(defn- audit-record
  "The audit tail every decision returns: what was decided, against which gates,
  and the explicit no-physical-command attestation."
  [activity-id decision refusal gates-checked effect]
  {:audit/activity-id activity-id
   :audit/decision decision
   :audit/refusal refusal
   :audit/gates-checked gates-checked
   :audit/effect effect
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

;; ── activity: die-preparation plan (hazardous — human approval required) ───

(defn plan-die-preparation
  "One magnesium-HPDC die-preparation activity.

  `req` keys (all measured values must be supplied by the caller; this function
  invents none):
    :activity/id              string
    :die                      {:die-id string
                               :measured-thermal-cycles integer (counted on the
                                 die, not estimated)
                               :max-thermal-cycles integer (die-life limit from
                                 the die's recorded specification — required as
                                 an input, never defaulted here)
                               :crack-inspection {:method string
                                                  :result :pass|:fail
                                                  :inspected-at string}}
    :lineage-cids             vector of upstream CIDs (G14 die-attestation
                              chain: die design CAD CID etc.)
    :release-agent            {:lot-id string  :g7-scan-cleared? boolean}
    :measured-preheat-temp-c  number — measured at the die, not a literature
                              value
    :interlocks               collection of interlock keywords (machine guard,
                              hot-surface PPE, die crane)
    :witness-robot-dids       vector of ≥2 robot DIDs (G4)
    :human-approval           {:approver-did string  :approved-at string
                               :scope #{:die-handling :agent-spray} — must cover
                               both hazardous steps}
    :requested-effect         :simulate-plan (the only admissible kind) or
                              :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        die (get req :die)
        inspection (get die :crack-inspection)
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a die-preparation activity needs an :activity/id")

          (not (and (map? die) (present? (get die :die-id))))
          (do (note :die-declared)
              "die: a die-preparation activity needs a declared :die with a :die-id")

          (not (and (integer? (get die :measured-thermal-cycles))
                    (not (neg? (get die :measured-thermal-cycles)))))
          (do (note :measured-thermal-cycles-required)
              "unmeasured: :measured-thermal-cycles must be a counted value from the die's own history; this module never estimates die wear")

          (not (integer? (get die :max-thermal-cycles)))
          (do (note :die-life-limit-required)
              "unmeasured: :max-thermal-cycles must be supplied from the die's recorded specification; this module never substitutes a default die-life constant")

          (>= (get die :measured-thermal-cycles) (get die :max-thermal-cycles))
          (do (note :die-life-exhausted)
              (str "safety: die "
                   (pr-str (get die :die-id))
                   " is at or past its recorded life ("
                   (get die :measured-thermal-cycles)
                   "/"
                   (get die :max-thermal-cycles)
                   " thermal cycles); a life-exhausted die holding molten magnesium is refused outright"))

          (not (and (map? inspection)
                    (present? (get inspection :method))
                    (present? (get inspection :inspected-at))
                    (contains? recognized-crack-results (get inspection :result))))
          (do (note :crack-inspection-recorded)
              (str "safety: a recorded crack inspection (method, result "
                   (pr-str (sort (map name recognized-crack-results)))
                   ", inspected-at) is required before a die is prepared"))

          (= :fail (get inspection :result))
          (do (note :crack-result-fail-refused)
              "safety: cracked die refused outright — a cracked die under shot pressure is a molten-magnesium ejection hazard")

          (not (and (map? (get req :release-agent))
                    (present? (get-in req [:release-agent :lot-id]))
                    (true? (get-in req [:release-agent :g7-scan-cleared?]))))
          (do (note :g7-release-agent-cleared)
              "G7: the release-agent lot must be declared with a recorded forbidden-substance scan result (:g7-scan-cleared? true)")

          (not (number? (get req :measured-preheat-temp-c)))
          (do (note :measured-preheat-temp-required)
              "unmeasured: :measured-preheat-temp-c is required and must be measured at the die; this module never substitutes a literature value")

          (not (and (vector? (get req :lineage-cids))
                    (every? present? (get req :lineage-cids))
                    (seq (get req :lineage-cids))))
          (do (note :g14-lineage-cids)
              "G14: the die-attestation lineage (e.g. die design CAD CID) must be recorded as non-empty :lineage-cids")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs ≥ " g4-min-witness-robots
                   " robot signers per record"))

          (= :command-machine (get req :requested-effect))
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (and (map? (get req :human-approval))
                    (present? (get-in req [:human-approval :approver-did]))
                    (present? (get-in req [:human-approval :approved-at]))
                    (set/subset? human-approval-steps
                                 (set (map keyword
                                           (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              (str "human-approval: die handling (suspended load, hot surface) and agent spray over a hot cavity are hazardous operations; a named human approver with "
                   (pr-str (sort (map name human-approval-steps)))
                   " scope must be recorded — absence defers, never approves"))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:die (dissoc die :crack-inspection)
                                  :crack-inspection inspection
                                  :release-agent (:release-agent req)
                                  :measured-preheat-temp-c (:measured-preheat-temp-c req)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :lineage-cids (vec (:lineage-cids req))
                                  :witness-robot-dids (vec (:witness-robot-dids req))
                                  :unmeasured-fields [:die-life-consumption-rate
                                                      :release-agent-consumption
                                                      :cycle-time :yield :price]}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

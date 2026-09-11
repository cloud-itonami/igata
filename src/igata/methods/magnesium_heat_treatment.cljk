(ns igata.methods.magnesium-heat-treatment
  "magnesium_heat_treatment.cljc — heat-treatment cell decision contract
  (:magnesium-hpdc manufacturing cell, `igata_heat_treatment` in the
  8-cell linear chain; scripts/hermes-magnesium-systems-bots/system-scope.edn
  on com-junkawasaki origin/main).

  Scope note: `magnesium-trim-qc.cljc` and `magnesium-solidify-eject.cljc`
  own the cells BEFORE this one; `igata_part_attestation` (final lineage)
  follows AFTER it. This module owns the heat-treatment step itself:

    activity 1 — plan-heat-treatment (T5 artificial aging / T6 solution +
                 aging / stress-relief on a magnesium casting, in an
                 air/inert atmosphere furnace)
    activity 2 — screen-furnace-offer (procurement screening for the cell's
                 heat-treatment furnace equipment class)

  Hazard boundaries encoded (molten-adjacent magnesium, combustible dust):
    - nitrate/nitrite SALT-BATH treatment of magnesium parts is REFUSED
      outright (salt baths are an established explosion hazard with Mg and
      are prohibited for magnesium heat treatment — atmosphere furnaces
      only)
    - water-quench medium is refused for magnesium (T6 quench medium must
      be declared; water contact with hot magnesium-rich parts/dust is a
      hazard the cell does not accept in this first-generation slice)
    - every thermal parameter must be a measured or machine-documented
      value supplied by the caller; this module NEVER substitutes a
      literature constant for a missing setpoint, soak time, or capacity
    - over-temperature alarm, Class-D fire suppression and no-water-contact
      interlocks must be live before a plan is approved
    - heat treatment of castings is hazardous furnace operation: a named
      human approval with :heat-treat scope is required; absence defers,
      never approves
    - the only admissible effect kind is :simulate-plan;
      :command-machine is refused unconditionally (the bot may design and
      simulate; it may NOT command physical equipment)
    - procurement is a financial commitment: the screening decision is
      ALWAYS :deferred to a human approver; condition must be distinguished
      as new / used / refurbished / unknown; missing price / lead-time /
      utility / safety / compliance values are recorded as :unmeasured

  Constitutional parity with manifest.edn gates: G2 (composition fully
  disclosed), G4 (witness quorum >= 2 robot signers).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g4-min-witness-robots 2)
(def ^:private recognized-mg-alloy-families #{"AZ91D" "AM50" "AM60"})
(def ^:private recognized-treatment-types #{:t5 :t6 :stress-relief})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})
(def ^:private forbidden-bath-kinds #{:nitrate-salt :nitrite-salt :salt-bath})
(def ^:private forbidden-quench-media #{:water :brine})
(def ^:private required-interlocks
  #{:overtemp-alarm :class-d-extinguisher :no-water-contact})
(def ^:private recognized-atmospheres #{:air :inert-gas})

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

(defn- human-approval-ok? [req scope]
  (and (map? (get req :human-approval))
       (present? (get-in req [:human-approval :approver-did]))
       (present? (get-in req [:human-approval :approved-at]))
       (contains? (set (map keyword (get-in req [:human-approval :scope])))
                  scope)))

(defn- machine-command-refusal [req]
  (= :command-machine (get req :requested-effect)))

;; ── activity 1: heat-treatment plan (hazardous — human approval required) ──

(defn plan-heat-treatment
  "One magnesium heat-treatment activity (T5 / T6 / stress-relief).

  `req` keys (all measured values must be supplied by the caller; this
  function invents none):
    :activity/id            string
    :alloy                  {:family string  :composition-disclosed? boolean}
    :treatment-type         :t5 | :t6 | :stress-relief
    :bath                   {:kind :air-furnace | :inert-gas-furnace | ...}
                            — a nitrate/nitrite salt bath is refused outright
    :quench                 {:medium keyword} — required for :t6 only;
                            water/brine refused
    :atmosphere             :air | :inert-gas
    :measured-setpoint-c    number — the furnace setpoint AS MEASURED at the
                            furnace controller, not a literature value
    :measured-soak-minutes  number — soak time from the documented process
                            sheet, never a module default
    :interlocks             collection of interlock keywords (overtemp alarm,
                            Class-D extinguisher, no-water-contact)
    :witness-robot-dids     vector of >=2 robot DIDs (G4)
    :human-approval         {:approver-did string  :approved-at string
                             :scope containing :heat-treat}
    :requested-effect       :simulate-plan (the only admissible kind) or
                            :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        treatment (some-> (get req :treatment-type) keyword)
        bath-kind (some-> (get-in req [:bath :kind]) keyword)
        quench-medium (some-> (get-in req [:quench :medium]) keyword)
        atmosphere (some-> (get req :atmosphere) keyword)
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a heat-treatment activity needs an :activity/id")

          (not (and (map? (get req :alloy))
                    (contains? recognized-mg-alloy-families
                               (get-in req [:alloy :family] ""))))
          (do (note :g2-magnesium-alloy-family)
              (str "G2-magnesium: alloy family must be one of "
                   (pr-str (sort recognized-mg-alloy-families))
                   " with a disclosed composition; got "
                   (pr-str (get-in req [:alloy :family]))))

          (not (true? (get-in req [:alloy :composition-disclosed?])))
          (do (note :g2-composition-disclosed)
              "G2: alloy composition must be fully disclosed (no proprietary closed alloys)")

          (not (contains? recognized-treatment-types treatment))
          (do (note :treatment-type-recognized)
              (str "treatment-type: must be one of "
                   (pr-str (sort recognized-treatment-types))
                   "; got " (pr-str (get req :treatment-type))))

          (contains? forbidden-bath-kinds bath-kind)
          (do (note :nitrate-salt-bath-forbidden)
              "safety: nitrate/nitrite salt-bath treatment of magnesium parts is refused outright (established explosion hazard with magnesium); atmosphere furnaces only")

          (not (present? bath-kind))
          (do (note :bath-declared)
              "safety: the treatment furnace medium (:bath :kind) must be declared; undeclared atmosphere is refused")

          (and (= :t6 treatment) (nil? quench-medium))
          (do (note :t6-quench-medium-required)
              "safety: a T6 treatment must declare its quench medium (:quench :medium); undeclared quench is refused")

          (contains? forbidden-quench-media quench-medium)
          (do (note :water-quench-forbidden)
              "safety: water/brine quench of magnesium-rich parts is refused (water contact with hot magnesium is a hazard this first-generation cell does not accept)")

          (not (contains? recognized-atmospheres atmosphere))
          (do (note :atmosphere-declared)
              (str "atmosphere: must be one of "
                   (pr-str (sort recognized-atmospheres))
                   "; got " (pr-str (get req :atmosphere))))

          (not (number? (get req :measured-setpoint-c)))
          (do (note :measured-setpoint-required)
              "unmeasured: :measured-setpoint-c is required and must be measured at the furnace controller; this module never substitutes a literature constant")

          (not (number? (get req :measured-soak-minutes)))
          (do (note :measured-soak-required)
              "unmeasured: :measured-soak-minutes is required and must come from the documented process sheet; this module never defaults a soak time")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   g4-min-witness-robots))
          (do (note :g4-witness-quorum)
              (str "G4: witness quorum needs >= " g4-min-witness-robots
                   " robot signers per record"))

          (machine-command-refusal req)
          (do (note :no-physical-command)
              "no-physical-command: the bot may design and simulate but may not command physical equipment; only :simulate-plan is admissible")

          (not (human-approval-ok? req :heat-treat))
          (do (note :human-approval-required)
              "human-approval: heat treatment is hazardous furnace operation; a named human approver with :heat-treat scope must be recorded — absence defers, never approves")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:alloy (:alloy req)
                                  :treatment-type treatment
                                  :bath {:kind bath-kind}
                                  :quench (when quench-medium {:medium quench-medium})
                                  :atmosphere atmosphere
                                  :measured-setpoint-c (:measured-setpoint-c req)
                                  :measured-soak-minutes (:measured-soak-minutes req)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :witness-robot-dids (vec (:witness-robot-dids req))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: furnace-offer screening (procurement — always deferred) ────

(defn screen-furnace-offer
  "Screen one heat-treatment furnace offer for the cell. Procurement is a
  financial commitment: the decision is ALWAYS :deferred to a human approver;
  this fn only assembles the auditable evidence record. Condition must be
  distinguished as \"new\", \"used\", \"refurbished\" or \"unknown\". Missing
  price / lead-time / utility / safety / compliance values are recorded as
  :unmeasured — never invented."
  [offer]
  (let [activity-id (get offer :activity/id "")
        gates (atom [])
        condition (get offer :condition)
        source-url (get offer :source-url)]
    (swap! gates conj :condition-distinguished :source-recorded)
    (cond
      (not (present? activity-id))
      (refuse activity-id "activity-id: a furnace screening needs an :activity/id" @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions))
                   "; got " (pr-str condition))
              @gates)

      (not (present? source-url))
      (refuse activity-id "source: an offer needs a first-party :source-url" @gates)

      :else
      (let [effect {:effect/kind :deferred-human-approval
                    :effect/machine-command false
                    :effect/screening
                    {:manufacturer (get offer :manufacturer)
                     :model (get offer :model)
                     :equipment-class (get offer :equipment-class)
                     :condition condition
                     :seller (get offer :seller)
                     :source-url source-url
                     :observed-at (get offer :observed-at)
                     :unmeasured (dissoc (select-keys offer [:price :currency :lead-time
                                                             :utility :safety :compliance])
                                         nil)
                     :unmeasured-fields [:price :currency :lead-time :utility :safety :compliance]}}]
        {:decision :deferred
         :effect effect
         :audit (audit-record activity-id :deferred
                              "procurement is a human decision; screening evidence assembled only"
                              (conj @gates :human-approval-required :no-financial-commitment)
                              effect)}))))

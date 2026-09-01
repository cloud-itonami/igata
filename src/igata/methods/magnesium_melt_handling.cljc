(ns igata.methods.magnesium-melt-handling
  "Melt-furnace tending and dosing-transfer decision contract for the
  magnesium-HPDC cell (activity -> decision -> effect -> audit).

  Scope note: `magnesium-hpdc.cljc` owns the melt-and-*shot* plan. This module
  owns the *furnace-side* hazardous activities that precede it and that the
  shot plan assumes as preconditions:
    activity 1 — tend-melt-furnace (solid Mg ingot charging, crucible check,
                 over-temperature / cover-gas-loss interlock verification)
    activity 2 — plan-dosing-transfer (measured-mass transfer from furnace to
                 dosing ladle / shot sleeve feed)

  Boundaries (mirrors magnesium-hpdc.cljc):
    - never invents a numeric limit, yield, capacity, or cycle time; every
      threshold the caller asserts must be a measured or machine-documented
      value supplied in the request
    - the only admissible effect kind is :simulate-plan; :command-machine is
      refused unconditionally (design/simulate only, never command equipment)
    - melt handling over molten magnesium is hazardous: a named human approval
      with the matching scope is required; absence defers, never approves"
  (:require [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.time Instant)))

(def ^:private min-witness-robots 2)

;; Interlocks every furnace-tending activity must have live. These mirror the
;; required-interlocks of magnesium-hpdc.cljc plus the furnace-specific alarms.
(def ^:private required-tending-interlocks
  #{:dry-dust-collection
    :class-d-extinguisher
    :no-water-contact
    :furnace-overtemp-alarm
    :cover-gas-loss-alarm})

;; Dosing/transfer happens over open molten metal but inside the guarded cell.
(def ^:private required-dosing-interlocks
  #{:dry-dust-collection
    :no-water-contact
    :machine-guard-verified
    :cover-gas-loss-alarm})

(def ^:private recognized-charging-atmospheres #{:dry :dry-inert})

(defn- present? [x]
  (or (some-> x str/trim not-empty)
      (and (number? x) (not (Double/isNaN (double x))))))

(defn- audit-record [activity-id outcome refusal gates effect]
  {:audit/id (str "audit-" activity-id)
   :audit/outcome outcome
   :audit/refusal refusal
   :audit/gates-checked (vec (distinct gates))
   :audit/approved-at (str (Instant/now))
   :audit/effect effect
   :audit/bot-commanded-equipment false})

(defn- refuse [activity-id refusal gates]
  {:decision :refused
   :effect {:effect/kind :none :effect/machine-command false}
   :audit (audit-record activity-id :refused refusal gates {:effect/kind :none})})

(defn- human-approval-ok? [req scope-key]
  (and (map? (get req :human-approval))
       (present? (get-in req [:human-approval :approver-did]))
       (present? (get-in req [:human-approval :approved-at]))
       (contains? (set (map keyword (get-in req [:human-approval :scope])))
                  scope-key)))

(defn- machine-command-refusal [req]
  (when (= :command-machine (get req :requested-effect))
    "no-physical-command: the bot may design and simulate but may not command
     physical equipment; only :simulate-plan is admissible"))

;; ── activity 1: tend-melt-furnace (hazardous — human approval :furnace) ────

(defn tend-melt-furnace
  "One magnesium melt-furnace tending activity (ingot charging, crucible
  check, interlock verification).

  `req` keys (all thresholds must be measured or machine-documented values;
  this function invents none):
    :activity/id                string
    :charging                   {:ingot-alloy string
                                 :charging-atmosphere :dry|:dry-inert
                                 :moisture-inspected? boolean}
    :crucible                   {:condition-inspected? boolean
                                  :inspection-at string}
    :measured-melt-temp-c       number — measured at the furnace
    :measured-overtemp-limit-c  number — the furnace's own documented limit
    :cover-gas                  {:agent string :measured-flow-lmin number}
    :interlocks                 collection of interlock keywords
    :witness-robot-dids         vector of >=2 robot DIDs
    :human-approval             {:approver-did string :approved-at string
                                 :scope must contain :furnace}
    :requested-effect           :simulate-plan (only admissible) or
                                :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        measured-temp (get req :measured-melt-temp-c)
        overtemp-limit (get req :measured-overtemp-limit-c)
        charging (get req :charging)
        crucible (get req :crucible)
        cover-gas (get req :cover-gas)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a furnace-tending activity needs an :activity/id")

          (not (and (map? charging)
                    (present? (get charging :ingot-alloy))))
          (do (note :charging-declared)
              "charging: the ingot alloy being charged must be declared")

          (not (contains? recognized-charging-atmospheres
                          (keyword (get charging :charging-atmosphere :wet))))
          (do (note :charging-atmosphere-dry)
              "safety: solid magnesium ingot charging requires a declared dry
               or dry-inert atmosphere; wet or unspecified atmosphere over
               magnesium surfaces is refused (water contact hazard)")

          (not (true? (get charging :moisture-inspected?)))
          (do (note :charging-moisture-inspected)
              "safety: charge material must be moisture-inspected before
               charging; un-inspected charge material is refused (steam
               explosion hazard over molten magnesium)")

          (not (and (map? crucible)
                    (true? (get crucible :condition-inspected?))
                    (present? (get crucible :inspection-at))))
          (do (note :crucible-inspection-required)
              "unmeasured: crucible condition must be inspected with a dated
               inspection record; an unknown crucible condition is refused,
               never assumed acceptable")

          (not (number? measured-temp))
          (do (note :measured-melt-temp-required)
              "unmeasured: :measured-melt-temp-c is required and must be
               measured at the furnace; this module never substitutes a
               literature constant")

          (not (number? overtemp-limit))
          (do (note :overtemp-limit-required)
              "unmeasured: :measured-overtemp-limit-c must be the furnace's
               own documented limit, supplied by the caller; this module does
               not invent a temperature limit")

          (> (double measured-temp) (double overtemp-limit))
          (do (note :measured-temp-within-limit)
              "safety: measured melt temperature exceeds the furnace's
               documented over-temperature limit; tending is refused until the
               melt cools within the measured limit")

          (not (and (map? cover-gas)
                    (present? (get cover-gas :agent))
                    (number? (get cover-gas :measured-flow-lmin))))
          (do (note :cover-gas-declared-and-measured)
              "safety: molten magnesium requires a declared inert-class cover
               gas with a measured flow; air-exposed melt is refused")

          (str/includes? (str/lower-case (str (get cover-gas :agent))) "water")
          (do (note :cover-gas-not-water-based)
              "safety: water-based atmosphere over molten magnesium is refused
               outright (explosive contact hazard)")

          (not (set/subset? required-tending-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: furnace-tending interlocks incomplete; required "
                   (pr-str (sort required-tending-interlocks))
                   " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   min-witness-robots))
          (do (note :witness-quorum)
              (str "witness: witness quorum needs >= "
                   min-witness-robots " robot signers per record"))

          (not (human-approval-ok? req :furnace))
          (do (note :human-approval-required)
              "human-approval: furnace tending over molten magnesium is
               hazardous; a named human approver with :furnace scope must be
               recorded — absence defers, never approves")

          (machine-command-refusal req)
          (do (note :no-physical-command)
              (machine-command-refusal req))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:charging {:ingot-alloy (get charging :ingot-alloy)
                                             :charging-atmosphere (keyword (get charging :charging-atmosphere))
                                             :moisture-inspected? true}
                                  :crucible crucible
                                  :measured-melt-temp-c measured-temp
                                  :measured-overtemp-limit-c overtemp-limit
                                  :cover-gas cover-gas
                                  :interlocks (sort (set (map keyword (:interlocks req))))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: plan-dosing-transfer (hazardous — human approval :dosing) ──

(defn plan-dosing-transfer
  "One measured-mass dosing/transfer activity from melt furnace to dosing
  ladle / shot-sleeve feed.

  `req` keys (all values measured or machine-documented; nothing invented):
    :activity/id            string
    :measured-mass-g        number — measured target dose mass
    :dosing-method          string (e.g. \"robot-ladle\" — declared, not chosen
                            by this module)
    :measured-melt-temp-c   number — measured at the furnace
    :cover-gas              {:agent string :measured-flow-lmin number}
    :interlocks             collection of interlock keywords
    :witness-robot-dids     vector of >=2 robot DIDs
    :human-approval         {:approver-did string :approved-at string
                             :scope must contain :dosing}
    :requested-effect       :simulate-plan (only admissible) or
                            :command-machine (refused unconditionally)"
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        mass (get req :measured-mass-g)
        cover-gas (get req :cover-gas)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a dosing-transfer activity needs an :activity/id")

          (not (and (number? mass) (pos? (double mass))))
          (do (note :measured-mass-required)
              "unmeasured: :measured-mass-g is required and must be a positive
               measured value; this module never invents a dose mass")

          (not (present? (get req :dosing-method)))
          (do (note :dosing-method-declared)
              "dosing-method: the transfer method must be declared by the
               caller; this module does not select or invent a method")

          (not (number? (get req :measured-melt-temp-c)))
          (do (note :measured-melt-temp-required)
              "unmeasured: :measured-melt-temp-c is required and must be
               measured at the furnace; this module never substitutes a
               literature constant")

          (not (and (map? cover-gas)
                    (present? (get cover-gas :agent))
                    (number? (get cover-gas :measured-flow-lmin))))
          (do (note :cover-gas-declared-and-measured)
              "safety: transfer over open molten magnesium requires a declared
               inert-class cover gas with a measured flow")

          (not (set/subset? required-dosing-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: dosing-transfer interlocks incomplete; required "
                   (pr-str (sort required-dosing-interlocks))
                   " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                   min-witness-robots))
          (do (note :witness-quorum)
              (str "witness: witness quorum needs >= "
                   min-witness-robots " robot signers per record"))

          (not (human-approval-ok? req :dosing))
          (do (note :human-approval-required)
              "human-approval: molten-metal transfer is hazardous; a named
               human approver with :dosing scope must be recorded — absence
               defers, never approves")

          (machine-command-refusal req)
          (do (note :no-physical-command)
              (machine-command-refusal req))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:measured-mass-g mass
                                  :dosing-method (get req :dosing-method)
                                  :measured-melt-temp-c (get req :measured-melt-temp-c)
                                  :cover-gas cover-gas
                                  :interlocks (sort (set (map keyword (:interlocks req))))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

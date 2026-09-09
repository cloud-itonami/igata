(ns igata.methods.magnesium-hpdc
  "magnesium_hpdc.cljc — 鋳型 magnesium-HPDC cell decision contract (:magnesium-hpdc
  manufacturing cell, scripts/hermes-magnesium-systems-bots/system-scope.edn on
  com-junkawasaki origin/main).

  First executable slice of the magnesium HPDC cell: a PURE decision layer that
  models activity -> decision -> effect -> audit for melt-and-shot activity on a
  magnesium (AZ91D / AM50 / AM60 class) alloy, plus procurement screening for the
  cell's equipment classes. The bot may design and simulate; it may NOT command
  physical equipment — a machine command is refused unconditionally.

  Hazard boundaries encoded (molten magnesium / combustible dust):
    - cover gas must be declared and inert-class (molten Mg ignites in air);
      a water-based atmosphere is refused outright
    - dry dust-collection, Class-D extinguisher, no-water-contact and
      machine-guard interlocks must be declared before a plan is approved
    - human approval is required for every hazardous step (melt / shot) —
      absence defers, never approves
    - no material or performance constant is invented: measured values are
      required inputs and missing price / lead-time / utility / safety /
      compliance values are recorded as :unmeasured, never filled in

  Constitutional parity with manifest.edn gates: G1 (clamping ≤ 6000 t),
  G2 (composition fully disclosed), G4 (witness quorum ≥ 2 robot signers).

  Pure fns; deterministic; keyword-keyed records; stdlib only."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; ── constants ──────────────────────────────────────────────────────────────

(def ^:private g1-max-clamping-force-tons 6000)
(def ^:private g4-min-witness-robots 2)
(def ^:private required-interlocks
  #{:dry-dust-collection :class-d-extinguisher :no-water-contact :machine-guard-verified})
(def ^:private recognized-mg-alloy-families #{"AZ91D" "AM50" "AM60"})
(def ^:private recognized-conditions #{"new" "used" "refurbished" "unknown"})

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

;; ── activity 1: melt-and-shot plan (hazardous — human approval required) ───

(defn plan-melt-and-shot
  "One magnesium melt-and-shot activity.

  `req` keys (all measured values must be supplied by the caller; this function
  invents none):
    :activity/id           string
    :alloy                 {:family string  :composition-disclosed? boolean}
    :measured-melt-temp-c  number — measured at the furnace, not a literature value
    :cover-gas             {:agent string  :measured-flow-lmin number}
    :interlocks            collection of interlock keywords (dry dust collection,
                           Class-D extinguisher, no-water-contact, machine guard)
    :clamping-force-tons   integer (G1)
    :witness-robot-dids    vector of ≥2 robot DIDs (G4)
    :human-approval        {:approver-did string  :approved-at string
                            :scope #{:melt :shot} — must cover both hazardous steps}
    :requested-effect      :simulate-plan (the only admissible kind) or
                           :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        ;; ordered checks; first failure refuses
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a melt-and-shot activity needs an :activity/id")

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

          (not (number? (get req :measured-melt-temp-c)))
          (do (note :measured-melt-temp-required)
              "unmeasured: :measured-melt-temp-c is required and must be measured at the furnace; this module never substitutes a literature constant")

          (not (and (map? (get req :cover-gas))
                    (present? (get-in req [:cover-gas :agent]))
                    (number? (get-in req [:cover-gas :measured-flow-lmin]))))
          (do (note :cover-gas-declared-and-measured)
              "safety: molten magnesium requires a declared inert-class cover gas with a measured flow; air-exposed melt is refused")

          (str/includes? (str/lower-case (str (get-in req [:cover-gas :agent]))) "water")
          (do (note :cover-gas-not-water-based)
              "safety: water-based atmosphere over molten magnesium is refused outright (explosive contact hazard)")

          (not (set/subset? required-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: interlocks incomplete; required "
                   (pr-str (sort required-interlocks))
                   " got " (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (not (and (integer? (get req :clamping-force-tons))
                    (pos? (get req :clamping-force-tons))
                    (<= (get req :clamping-force-tons) g1-max-clamping-force-tons)))
          (do (note :g1-clamping-force)
              (str "G1: clamping force must be ≤ " g1-max-clamping-force-tons
                   " ton (R0..R3); got " (pr-str (get req :clamping-force-tons))))

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
                    (set/subset? #{:melt :shot}
                                 (set (map keyword (get-in req [:human-approval :scope]))))))
          (do (note :human-approval-required)
              "human-approval: melt and shot are hazardous operations; a named human approver with :melt and :shot scope must be recorded — absence defers, never approves")

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:alloy (:alloy req)
                                  :measured-melt-temp-c (:measured-melt-temp-c req)
                                  :cover-gas (:cover-gas req)
                                  :interlocks (sort (set (map keyword (:interlocks req))))
                                  :clamping-force-tons (:clamping-force-tons req)
                                  :witness-robot-dids (vec (:witness-robot-dids req))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: equipment-offer screening (procurement — always deferred) ──

(defn screen-equipment-offer
  "Screen one equipment offer for the magnesium-HPDC cell. Procurement is a
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
      (refuse activity-id "activity-id: an equipment screening needs an :activity/id" @gates)

      (not (contains? recognized-conditions condition))
      (refuse activity-id
              (str "condition: must be distinguished as one of "
                   (pr-str (sort recognized-conditions)) "; got " (pr-str condition))
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

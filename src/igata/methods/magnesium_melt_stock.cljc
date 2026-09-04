(ns igata.methods.magnesium-melt-stock
  "Melt-stock qualification (screening & revert routing) decision contract for
  the magnesium-HPDC cell (activity -> decision -> effect -> audit).

  Boundary note: `magnesium-melt-handling.cljc` owns the *furnace-side*
  hazardous activities (ingot charging into the crucible, dosing transfer).
  This module owns the step *before* the furnace: deciding which incoming
  magnesium material streams are eligible to be charged at all.

    activity 1 — screen-melt-stock (classify a declared lot as charge-eligible,
                 revert-hold, or refused for the remelt stream)
    activity 2 — plan-revert-hold (route a held lot to a declared dry hold
                 location pending a human disposition)

  Material streams recognized (declared by the caller, never inferred):
    :new-ingot         — primary ingot; requires a mill/alloy attestation ref
    :trim-revert       — trim-QC output (gates/runners/overspray) referenced to
                         a trim-QC record; screening is a caller-measured input
    :gate-runner-revert— gate/runner revert referenced to a trim-QC record
    :dross             — melt dross; refused for the remelt stream outright
                         (oxidized material with entrained molten-metal pockets
                         is not an eligible remelt input; routing dross is a
                         hazardous-waste disposition that needs a named human
                         owner — this module refuses, never plans it)

  Boundaries (mirrors magnesium-melt-handling.cljc):
    - never invents a numeric limit, yield, capacity, or cycle time; every
      threshold the caller asserts must be a measured or machine-documented
      value supplied in the request
    - the only admissible effect kind is :simulate-plan; :command-machine is
      refused unconditionally (design/simulate only, never command equipment)
    - handling magnesium revert/dross carries combustible-dust and
      water-contact hazards: a named human approval with the matching scope is
      required; absence defers, never approves"
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- now-str
  "Wall-clock ISO-8601 instant string, portable across bb (JVM) and nbb
  (JS). Never used as a safety input — audit attribution only."
  []
  (str #?(:clj (java.time.Instant/now) :cljs (js/Date.))))

(def ^:private min-witness-robots 2)

(def ^:private recognized-source-classes
  #{:new-ingot :trim-revert :gate-runner-revert :dross})

(def ^:private revert-classes #{:trim-revert :gate-runner-revert})

;; Screening happens in the finishing/stock area: combustible Mg dust is
;; present (chips, gate/runner fines), water contact is the steam-explosion
;; hazard, and Class D coverage is required for metal fires.
(def ^:private required-screening-interlocks
  #{:dry-dust-collection
    :class-d-extinguisher
    :no-water-contact})

(defn- present? [x]
  (or (some-> x str/trim not-empty)
      (and (number? x)
           (not #?(:clj (Double/isNaN (double x))
                   :cljs (js/isNaN x))))))

(defn- audit-record [activity-id outcome refusal gates effect]
  {:audit/id (str "audit-" activity-id)
   :audit/outcome outcome
   :audit/refusal refusal
   :audit/gates-checked (vec (distinct gates))
   :audit/approved-at (now-str)
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

(defn- witness-quorum-refusal [req]
  (when-not (>= (count (remove str/blank? (map str (get req :witness-robot-dids))))
                min-witness-robots)
    (str "witness: witness quorum needs >= "
         min-witness-robots " robot signers per record")))

;; ── activity 1: screen-melt-stock (hazardous stream — human approval
;;    :melt-stock) ────────────────────────────────────────────────────────────

(defn screen-melt-stock
  "One melt-stock screening activity for a declared incoming lot.

  `req` keys (all values measured or machine-documented; nothing invented):
    :activity/id             string
    :stock                   {:lot-ref string
                              :source-class :new-ingot | :trim-revert |
                                            :gate-runner-revert | :dross
                              :alloy-family string
                              :moisture-inspected? boolean
                              ;; new-ingot only:
                              :mill-attestation-ref string (optional)
                              ;; revert classes only:
                              :trim-qc-record-ref string (optional)
                              :contamination-screen {:screened? boolean
                                                     :result :pass|:fail}}
    :melt-plan-alloy-family  string — the alloy family the furnace plan targets;
                             continuity with :stock :alloy-family is required
    :interlocks              collection of interlock keywords
    :witness-robot-dids      vector of >=2 robot DIDs
    :human-approval          {:approver-did string :approved-at string
                              :scope must contain :melt-stock}
    :requested-effect        :simulate-plan (only admissible) or
                             :command-machine (refused unconditionally)

  Returns {:decision :approved|:refused :effect {...} :audit {...}}.
  On approval the effect plan carries the routing: :charge-eligible for a
  lot whose declared screening result is :pass, :revert-hold when the
  declared result is :fail — the plan never claims the lot has been
  physically moved."
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        stock (get req :stock)
        source-class (keyword (get stock :source-class :unknown))
        screen (get stock :contamination-screen)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a melt-stock screening activity needs an
               :activity/id")

          (not (and (map? stock) (present? (get stock :lot-ref))))
          (do (note :stock-lot-declared)
              "stock: the lot being screened must be declared with a :lot-ref")

          (not (contains? recognized-source-classes source-class))
          (do (note :source-class-declared)
              "unmeasured: the material source class must be declared as one
               of :new-ingot / :trim-revert / :gate-runner-revert / :dross; an
               unknown or undeclared stream is refused, never assumed to be
               primary ingot")

          (= :dross source-class)
          (do (note :dross-not-remelt-eligible)
              "safety: melt dross is refused for the remelt stream — oxidized
               material with entrained molten-metal pockets is not an eligible
               remelt input, and dross routing is a hazardous-waste disposition
               requiring a named human owner; this module refuses and never
               plans dross handling")

          (and (= :new-ingot source-class)
               (not (present? (get stock :mill-attestation-ref))))
          (do (note :mill-attestation-required)
              "traceability: primary ingot requires a referenced mill/alloy
               attestation; an unattested primary stream is refused (G2/G14
               traceability chain must close before the furnace)")

          (and (contains? revert-classes source-class)
               (not (present? (get stock :trim-qc-record-ref))))
          (do (note :trim-qc-record-required)
              "traceability: revert material must reference the trim-QC record
               it originated from; unreferenced revert is refused (the trace
               chain from trim cell to melt cell must close)")

          (not (present? (get stock :alloy-family)))
          (do (note :stock-alloy-family-declared)
              "unmeasured: the lot's alloy family must be declared by the
               caller; this module never infers alloy identity from the lot
               ref")

          (not (present? (get req :melt-plan-alloy-family)))
          (do (note :melt-plan-alloy-family-declared)
              "unmeasured: the melt plan's target alloy family must be
               declared; this module does not assume continuity")

          (not= (get stock :alloy-family) (get req :melt-plan-alloy-family))
          (do (note :alloy-family-continuity)
              "traceability: the lot's declared alloy family does not match the
               melt plan's target family; charging is refused (alloy-family
               continuity must hold across the trim -> melt handoff)")

          (not (true? (get stock :moisture-inspected?)))
          (do (note :stock-moisture-inspected)
              "safety: melt stock must be moisture-inspected before being made
               charge-eligible; un-inspected stock is refused (steam explosion
               hazard when moisture reaches molten magnesium)")

          (not (and (map? screen) (true? (get screen :screened?))))
          (do (note :contamination-screen-required)
              "unmeasured: a contamination screening must be declared as
               performed (:screened? true) with a caller-measured :result; this
               module never assumes a lot is clean")

          (not (contains? #{:pass :fail} (keyword (get screen :result))))
          (do (note :contamination-screen-result-declared)
              "unmeasured: the screening :result must be declared as :pass or
               :fail; an unmeasured result is neither and is refused")

          (not (set/subset? required-screening-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: screening-area interlocks incomplete; required "
                   (pr-str (sort required-screening-interlocks))
                   " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (witness-quorum-refusal req)
          (do (note :witness-quorum)
              (witness-quorum-refusal req))

          (not (human-approval-ok? req :melt-stock))
          (do (note :human-approval-required)
              "human-approval: melt-stock screening over combustible
               magnesium-dust-bearing material is hazardous; a named human
               approver with :melt-stock scope must be recorded — absence
               defers, never approves")

          (machine-command-refusal req)
          (do (note :no-physical-command)
              (machine-command-refusal req))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [screen-failed? (and (map? screen) (true? (get screen :screened?))
                                (= :fail (keyword (get screen :result))))
            routing (if screen-failed? :revert-hold :charge-eligible)
            effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:stock {:lot-ref (get stock :lot-ref)
                                          :source-class source-class
                                          :alloy-family (get stock :alloy-family)
                                          :moisture-inspected? true}
                                  :melt-plan-alloy-family (get req :melt-plan-alloy-family)
                                  :routing routing
                                  :interlocks (sort (set (map keyword (:interlocks req))))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

;; ── activity 2: plan-revert-hold (hazardous storage routing — human approval
;;    :revert) ────────────────────────────────────────────────────────────────

(defn plan-revert-hold
  "One revert-hold routing activity for a lot that screening did not clear.

  `req` keys:
    :activity/id            string
    :stock                  {:lot-ref string
                             :source-class :trim-revert | :gate-runner-revert
                             :alloy-family string}
    :hold-reason            string (e.g. \"contamination-screen-fail\" —
                            declared, not chosen by this module)
    :hold-location          string — a declared dry hold location; this module
                            does not verify the location, it only records the
                            declaration and refuses if it is absent
    :measured-mass-g        number — measured lot mass (never invented)
    :interlocks             collection of interlock keywords (same set as
                            screening: combustible-dust / Class D / no water)
    :witness-robot-dids     vector of >=2 robot DIDs
    :human-approval         {:approver-did string :approved-at string
                             :scope must contain :revert}
    :requested-effect       :simulate-plan (only admissible) or
                            :command-machine (refused unconditionally)"
  [req]
  (let [activity-id (get req :activity/id "")
        gates (atom [])
        note (fn [g] (swap! gates conj g))
        stock (get req :stock)
        source-class (keyword (get stock :source-class :unknown))
        mass (get req :measured-mass-g)
        refusal
        (cond
          (not (present? activity-id))
          (do (note :activity-id-present)
              "activity-id: a revert-hold routing activity needs an
               :activity/id")

          (not (and (map? stock) (present? (get stock :lot-ref))))
          (do (note :stock-lot-declared)
              "stock: the held lot must be declared with a :lot-ref")

          (not (contains? revert-classes source-class))
          (do (note :revert-class-required)
              "traceability: only referenced revert streams (:trim-revert /
               :gate-runner-revert) may be routed to revert-hold; dross is
               refused outright and primary ingot never holds")

          (not (present? (get stock :trim-qc-record-ref)))
          (do (note :trim-qc-record-required)
              "traceability: a held revert lot must still reference its
               trim-QC record; the trace chain does not close by holding")

          (not (present? (get req :hold-reason)))
          (do (note :hold-reason-declared)
              "hold-reason: the reason the lot is held must be declared by the
               caller; this module does not select or invent a reason")

          (not (present? (get req :hold-location)))
          (do (note :hold-location-declared)
              "unmeasured: a hold location must be declared; this module does
               not verify the location and never assumes one is acceptable")

          (not (and (number? mass) (pos? (double mass))))
          (do (note :measured-mass-required)
              "unmeasured: :measured-mass-g is required and must be a positive
               measured value; this module never invents a lot mass")

          (not (set/subset? required-screening-interlocks
                            (set (map keyword (get req :interlocks)))))
          (do (note :interlocks-complete)
              (str "safety: revert-hold interlocks incomplete; required "
                   (pr-str (sort required-screening-interlocks))
                   " got "
                   (pr-str (sort (set (map keyword (get req :interlocks)))))))

          (witness-quorum-refusal req)
          (do (note :witness-quorum)
              (witness-quorum-refusal req))

          (not (human-approval-ok? req :revert))
          (do (note :human-approval-required)
              "human-approval: routing combustible magnesium revert material
               is hazardous; a named human approver with :revert scope must be
               recorded — absence defers, never approves")

          (machine-command-refusal req)
          (do (note :no-physical-command)
              (machine-command-refusal req))

          :else nil)]
    (if refusal
      (refuse activity-id refusal @gates)
      (let [effect {:effect/kind :simulate-plan-only
                    :effect/machine-command false
                    :effect/plan {:stock {:lot-ref (get stock :lot-ref)
                                          :source-class source-class
                                          :alloy-family (get stock :alloy-family)
                                          :trim-qc-record-ref (get stock :trim-qc-record-ref)}
                                  :hold-reason (get req :hold-reason)
                                  :hold-location (get req :hold-location)
                                  :measured-mass-g mass
                                  :interlocks (sort (set (map keyword (:interlocks req))))}}]
        {:decision :approved
         :effect effect
         :audit (audit-record activity-id :approved "" (conj @gates :human-approval-approved :no-physical-command) effect)}))))

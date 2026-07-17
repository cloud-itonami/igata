(ns igata.methods.test-charter-gates
  "igata — constitutional-gate conformance tests. Substrate-native Clojure (ADR-2606160842); 1:1 port of pruned test_charter_gates.py."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def ^:private here (.getParentFile (java.io.File. ^String *file*)))
(def ^:private root (.. here getParentFile getParentFile getParentFile))
(def ^:private lexdir (java.io.File. root "lex"))
(defn- lex [name] (edn/read-string (slurp (java.io.File. lexdir (str name ".edn")))))

(def ^:private military-tokens
  ["military" "armor" "armour" "fuselage" "firearm" "weapon"
   "munition" "warhead" "hull-plating" "missile" "gun"])
(def ^:private die-materials #{"H13-hot-work-tool-steel" "anviloy-1150-W-base-R3+"})

(defn- known [doc field]
  (let [acc (atom #{})]
    (letfn [(walk [x parent]
              (cond (map? x) (do (when (and (= parent field) (contains? x "knownValues"))
                                   (swap! acc into (get x "knownValues")))
                                 (doseq [[k v] x] (walk v k)))
                    (sequential? x) (doseq [v x] (walk v parent))))]
      (walk doc nil)) @acc))

(defn- required-union [doc]
  (let [acc (atom #{})]
    (letfn [(walk [x] (cond (map? x) (do (when (sequential? (get x "required")) (swap! acc into (get x "required"))) (doseq [v (vals x)] (walk v)))
                            (sequential? x) (doseq [v x] (walk v))))]
      (walk doc)) @acc))

;; ── G6 / N2 — no military / aerospace / armor part type ──
(deftest test-g6-no-military-part-type
  (let [parts (known (lex "partAttestation") "partType")
        low (set (map str/lower-case parts))]
    (is (seq parts) "partAttestation must enumerate partType")
    (doseq [tok military-tokens]
      (is (not (some #(str/includes? % tok) low))
          (str "G6/N2: part type '" tok "' must not be representable")))))

;; ── G7 — raw-material clearance scans mandatory ──
(deftest test-g7-raw-material-scans-required
  (let [req (required-union (lex "alloyAttestation"))]
    (doseq [field ["opcwScheduleScanPassed" "rohsScanPassed" "radioactiveScanPassed" "g7Scan"]]
      (is (contains? req field) (str "G7: alloyAttestation must require " field)))))

;; ── G4 — witness quorum (≥2 robot DIDs) on every attestation ──
(deftest test-g4-witness-quorum-on-all-records
  (doseq [name ["alloyAttestation" "castShotRecord" "dieAttestation" "partAttestation"]]
    (is (contains? (required-union (lex name)) "witnessRobotDids")
        (str "G4: " name " must require witnessRobotDids"))))

;; ── G9 — PFAS-free water-based die release; bounded die materials ──
(deftest test-g9-die-is-pfas-free-water-based
  (let [doc (lex "dieAttestation")
        req (required-union doc)
        mats (known doc "dieMaterial")]
    (doseq [field ["pfasFree" "waterBased" "lubricantFormulationG7"]]
      (is (contains? req field) (str "G9: dieAttestation must require " field)))
    (is (= mats die-materials) (str "die material must be exactly " die-materials ", got " mats))))

;; ── G8 — shot-replay determinism (full sensor profile @ 1 kHz logged) ──
(deftest test-g8-shot-replay-determinism
  (let [req (required-union (lex "castShotRecord"))]
    (doseq [field ["sensorStreamCid" "shotProfile" "slowPhase" "fastPhase"
                   "intensificationPhase" "pressureMpa" "velocityMs" "clampingForceTons"]]
      (is (contains? req field) (str "G8: castShotRecord must require " field " (deterministic shot replay)")))))

;; ── G14 — full lineage CID chain + material balance on the part ──
(deftest test-g14-part-lineage-chain
  (let [req (required-union (lex "partAttestation"))]
    (doseq [field ["alloyAttestationCid" "castShotRecordCid" "dieAttestationCid"
                   "qcAttestationCid" "lineage" "finalPhotoIpfsCid" "materialBalance" "recoveryRatio"]]
      (is (contains? req field) (str "G14: partAttestation must require " field)))))

;; ── G11 — operator vetting (operatorDid present on melt/shot/part) ──
(deftest test-g11-operator-attributed
  (doseq [name ["alloyAttestation" "castShotRecord" "partAttestation"]]
    (is (contains? (required-union (lex name)) "operatorDid")
        (str "G11: " name " must require operatorDid"))))

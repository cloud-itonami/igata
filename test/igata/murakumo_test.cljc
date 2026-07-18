(ns igata.murakumo-test
  (:require [clojure.test :refer [deftest is testing]]
            [igata.murakumo :as igata]))

(def full-attestations
  (into {}
        (map (fn [gate] [gate (str "attested-" (name gate))]))
        (distinct (mapcat :required-gates (vals igata/cell-specs)))))

(deftest maps-all-legacy-igata-cells
  (is (= #{"igata_alloy_melt"
           "igata_die_preparation"
           "igata_heat_treatment"
           "igata_part_attestation"
           "igata_post_cast_qc"
           "igata_shot_injection"
           "igata_solidification_eject"
           "igata_trim_machining"}
         (set (map :legacy-cell (vals igata/cell-specs))))))

(deftest r0-gates-block-effects
  (let [plan (igata/cell-plan :alloy-melt
                              {:lot-id "melt-001"
                               :computed-at "2026-06-29T00:00:00Z"})]
    (is (= :blocked (:status plan)))
    (is (= [:council-charter-attestation
            :silen-igata-baseline-review
            :hpdc-engineer-registry
            :metallurgist-registry
            :r1-activation-adr
            :robot-witness-quorum-baseline
            :ingot-provenance-g7-scan-baseline
            :electric-induction-furnace-baseline
            :icp-ms-oes-assay-baseline
            :high-pressure-gas-operator-baseline]
           (:missing-gates plan)))
    (is (empty? (:effects plan)))))

(deftest attested-shot-emits-mst-effect
  (let [plan (igata/cell-plan :shot-injection
                              {:attestations full-attestations
                               :lot-id "lot-alsi9-001"
                               :shot-id "shot-001"
                               :computed-at "2026-06-29T00:00:00Z"
                               :record {:tid "shot-001"
                                        :clampForceTons 500
                                        :sampleRateHz 1000}})
        effect (first (:effects plan))]
    (is (= :ready (:status plan)))
    (is (= :mst/put-record (:op effect)))
    (is (= igata/actor-did (:actor effect)))
    (is (= "com.etzhayyim.igata.castShotRecord" (:collection effect)))
    (is (= "shot-001" (:rkey effect)))
    (is (= 500 (get-in effect [:record :clampForceTons])))
    (is (= 1000 (get-in effect [:record :sampleRateHz])))))

(deftest special-gates-remain-cell-specific
  (testing "heat treatment remains R2-gated"
    (let [attestations (dissoc full-attestations :r2-ht-recipe-baseline)
          plan (igata/cell-plan :heat-treatment {:attestations attestations})]
      (is (= [:r2-ht-recipe-baseline] (:missing-gates plan)))
      (is (empty? (:effects plan)))))
  (testing "R1 part attestation blocks cross-actor message emission"
    (let [attestations (dissoc full-attestations :no-r1-cross-actor-messages-baseline)
          plan (igata/cell-plan :part-attestation {:attestations attestations})]
      (is (= [:no-r1-cross-actor-messages-baseline] (:missing-gates plan)))
      (is (empty? (:effects plan))))))

(deftest all-cell-plans-ready-when-attested
  (let [plans (igata/all-cell-plans {:attestations full-attestations
                                     :lot-id "lot-alsi9-001"
                                     :part-id "part-001"
                                     :shot-id "shot-001"
                                     :computed-at "2026-06-29T00:00:00Z"})]
    (is (= (set (keys igata/cell-specs)) (set (keys plans))))
    (is (every? #(= :ready (:status %)) (vals plans)))
    (is (= (count igata/cell-specs)
           (count (mapcat :effects (vals plans)))))))

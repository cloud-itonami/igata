#!/usr/bin/env bb
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[clojure.test :as t])
(let [root (fs/parent (fs/absolutize *file*))]
  (cp/add-classpath (str root "/src"))
  (cp/add-classpath (str root "/test")))
(def suites '[igata.methods.test-charter-gates igata.methods.test-magnesium-hpdc
              igata.methods.test-magnesium-trim-qc
              igata.methods.test-magnesium-melt-handling
              igata.methods.test-magnesium-solidify-eject
              igata.methods.test-magnesium-ndt-inspection
              igata.methods.test-magnesium-part-attestation
              igata.methods.test-magnesium-heat-treatment
              igata.methods.test-magnesium-melt-stock
              igata.methods.test-magnesium-cartridge-handling
              igata.repository-contract-test])
(apply require suites)
(let [{:keys [fail error]} (apply t/run-tests suites)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
              igata.methods.test-magnesium-shot-replay
              igata.methods.test-magnesium-die-preparation

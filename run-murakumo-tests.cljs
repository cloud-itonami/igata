#!/usr/bin/env nbb
;; nbb replacement for the retired bb.edn `test` task (ADR-2607173000).
;; Run with: nbb --classpath src:test run-murakumo-tests.cljs
(ns run-murakumo-tests
  (:require [clojure.test :as t]
            [igata.murakumo-test]))

(let [r (t/run-tests 'igata.murakumo-test)]
  (.exit js/process (if (zero? (+ (:fail r) (:error r))) 0 1)))

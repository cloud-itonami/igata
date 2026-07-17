(ns igata.repository-contract-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))
(defn- read-edn [path] (edn/read-string (slurp path)))
(deftest canonical-metadata-contract
  (let [manifest (read-edn "manifest.edn")
        identity (read-edn "identity.edn")
        contracts (read-edn "repository-contracts.edn")]
    (is (= "igata" (:actor/id manifest)))
    (is (= 14 (count (:actor/gates manifest))))
    (is (= "manifest.edn" (:canonical-actor-metadata contracts)))
    (is (not (.exists (io/file "manifest.jsonld"))))
    (is (= "did:web:etzhayyim.com:actor:igata" (:identity/did identity)))))
(deftest did-json-is-an-external-wire-artifact
  (let [identity (read-edn "identity.edn")
        wire (json/parse-string (slurp ".well-known/did.json"))]
    (is (= (:identity/did identity) (get wire "id")))
    (is (= (set (:identity/also-known-as identity))
           (set (get wire "alsoKnownAs"))))))

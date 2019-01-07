
(defproject event-data-evidence-log-snapshot "0.1.11"
  :description "Event Data Evidence Log Snapshot"
  :url "http://eventdata.crossref.org/"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [event-data-common "0.1.43"]
                 [overtone/at-at "1.2.0"]
                 [robert/bruce "0.8.0"]
                 [yogthos/config "0.8"]
                 [clj-time "0.12.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.slf4j/slf4j-simple "1.7.21"]
                 [org.apache.kafka/kafka-clients "0.10.2.0"]
                 [slingshot "0.12.2"]
                 [clojurewerkz/quartzite "2.0.0"]
                 [org.clojure/data.csv "0.1.4"]]
  :jvm-opts ["-Duser.timezone=UTC" "-Xmx4G"]
  :main ^:skip-aot event-data-evidence-log-snapshot.core
  :target-path "target/%s")
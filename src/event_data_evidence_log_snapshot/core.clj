(ns event-data-evidence-log-snapshot.core
  (:require [clojure.tools.logging :as log]
            [config.core :refer [env]]
            [event-data-common.storage.s3 :as s3]
            [event-data-common.storage.store :as store]
            [clojure.data.json :as json]
            [clj-time.core :as clj-time]
            [clj-time.coerce :as clj-time-coerce]
            [clj-time.format :as clj-time-format]
            [clj-time.periodic :as clj-time-periodic]
            [robert.bruce :refer [try-try-again]]
            [clojure.java.io :refer [writer]]
            [clojure.data.csv :as csv]
            [clojurewerkz.quartzite.triggers :as qt]
            [clojurewerkz.quartzite.jobs :as qj]
            [clojurewerkz.quartzite.schedule.daily-interval :as daily]
            [clojurewerkz.quartzite.schedule.calendar-interval :as cal]
            [clojurewerkz.quartzite.jobs :refer [defjob]]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.schedule.cron :as qc])
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:import [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords OffsetAndTimestamp]
           [org.apache.kafka.common TopicPartition PartitionInfo ]
           [java.io File]))

(defn retrieve-date-range
  "Callback each message for all messages between the two date, one message per callback. Callback
  is passed a ConsumerRecord.
  This will block until the end date if the date is in the future, so must only be called for dates
  in the past!

  Lines are written to the outputs stream verbatim, filtered by the timestamp assigned by Kafka.
  This means that the 't' field in the JSON, which is assigned by the code doing the logging in the
  first place, may have a little drift. The service is set up to expect one single partition for all
  status logging, which ensures that all messages are recorded in-order according to Kafka's
  semantics. 
  
  It is able to deal with multiple partitions, but iterates one partition at a time. The output file
  would therefore be split into chunks which have correct internal ordering."

  [start-date end-date topic-name callback]
  (let [start-timestamp (clj-time-coerce/to-long start-date)
        end-timestamp (clj-time-coerce/to-long end-date)

        ; Give every process a separate group so it's independent from any other instance or scan.
        group-id (str "live-demo" (System/currentTimeMillis))
        consumer (KafkaConsumer. 
                   {"bootstrap.servers" (:global-kafka-bootstrap-servers env)
                    "group.id" group-id
                    "key.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"
                    "value.deserializer" "org.apache.kafka.common.serialization.StringDeserializer"})

        ; List of org.apache.kafka.common.PartitionInfo.
        partition-infos (.partitionsFor consumer topic-name)
        ; List of org.apache.kafka.common.TopicPartition.
        topic-partitions (map #(TopicPartition. topic-name (.partition %)) partition-infos)
        ; Map of TopicPartition to OffsetAndTimestamp
        start-offsets (.offsetsForTimes consumer (into {} (map #(vector % start-timestamp) topic-partitions)))

        count-this-session (atom 0)]

      (log/info "Saving from" start-timestamp "to" end-timestamp "group" group-id "topic" topic-name)
      (log/info partition-infos)
      (log/info topic-partitions)
      (log/info start-offsets)

      (doseq [[^TopicPartition topic-partition ^OffsetAndTimestamp offset] start-offsets]
        (log/info "Saving for partition" topic-partition)
        (log/info "Assigning" topic-partition "and seeking" (.offset offset))

        (.assign consumer [topic-partition])
        (.seek consumer topic-partition (.offset offset))

        (log/info "Assignment now" (.assignment consumer))

        (log/info "Polling...")
        (loop []
          (let [^ConsumerRecords consumer-records (.poll consumer (int 10000))
                ; Only those records that occur before the end timestamp.
                relevant-records (filter #(< (.timestamp %) end-timestamp) consumer-records)]
            
            (doseq [record relevant-records]
              
              (callback record)

              ; We typically see a couple of million events per days.
              (swap! count-this-session inc)
              (when (zero? (rem @count-this-session 10000))
                (log/info "Written" @count-this-session "lines")))

            ; Could be empty because we got to a position past the timestamp, so it's time to stop.
            ; Otherwise, a timeout because we're in the future. Also time to stop.
            (when-not (empty? relevant-records)
              (recur)))))))

(def connection
  (delay
    (s3/build (:status-snapshot-s3-key env)
              (:status-snapshot-s3-secret env)
              (:status-snapshot-s3-region-name env)
              (:status-snapshot-s3-bucket-name env))))

(def date-format
  (clj-time-format/formatters :year-month-day))

(def max-historical-days
  "Catch up this many days when we run."
  ; Sensible default for daily use, we may want a different value for manual use.
  (if-let [max-days (:status-snapshot-max-days env)]
    (Integer/parseInt max-days)
    10))

(def csv-columns
  "The ordered list of fields that are included in a CSV file."
  [:t :s :c :f :i :p :r :a :v :d :n :u :e :o])

(def csv-column-selector
  "Map a log entry to a CSV line vector."
  (apply juxt csv-columns))

(defn ensure-day-evidence-logs
  [day-date]
  (let [ymd-string (clj-time-format/unparse date-format day-date)

        start-date (clj-time/date-time (clj-time/year day-date)
                                       (clj-time/month day-date)
                                       (clj-time/day day-date)
                                       0 0 0 0)
        end-date (clj-time/plus start-date (clj-time/days 1))

        client (:client @connection)
        bucket-name (:status-snapshot-s3-bucket-name env)
        txt-s3-file-path (str "log/" ymd-string ".txt")
        csv-s3-file-path (str "log/" ymd-string ".csv")]

    (if (.doesObjectExist client bucket-name txt-s3-file-path)
      (log/info "Text log file exists at " txt-s3-file-path ", skipping.")
      (let [file (File/createTempFile ymd-string ".txt")]
        (log/info "Saving log entries for" ymd-string "to temp file")

        (with-open [w (writer file)]
          (retrieve-date-range
            start-date
            end-date
            (:global-status-topic env)
            (fn [record]
              (.write w ^String (.value record))
              (.write w "\n"))))

          (log/info "Uploading Evidence Log text file to" txt-s3-file-path)
          (.putObject client bucket-name txt-s3-file-path file)
          (log/info "Done uploading Evidence Log text file.")

          (.delete file)))

    (if (.doesObjectExist client bucket-name csv-s3-file-path)
      (log/info "CSV log file exists at " csv-s3-file-path ", skipping.")
      (let [file (File/createTempFile ymd-string ".csv")]
        (log/info "Saving log entries for" ymd-string "to temp file")

        (with-open [w (writer file)]
          ; Write header.
          (csv/write-csv w [(map name csv-columns)])

          (retrieve-date-range
            start-date
            end-date
            (:global-status-topic env)
            (fn [record]
              (let [value (.value record)]
                (when-not (clojure.string/blank? value)
                  (csv/write-csv
                    w
                    [(csv-column-selector
                       (json/read-str ^String value :key-fn keyword))]))))))

          (log/info "Uploading Evidence Log CSV file to" csv-s3-file-path)
          (.putObject client bucket-name csv-s3-file-path file)
          (log/info "Done uploading Evidence Log CSV file.")

          (.delete file)))))


(defn ensure-day-evidence-records
  [day-date]
  (let [ymd-string (clj-time-format/unparse date-format day-date)

        start-date (clj-time/date-time (clj-time/year day-date)
                                       (clj-time/month day-date)
                                       (clj-time/day day-date)
                                       0 0 0 0)
        end-date (clj-time/plus start-date (clj-time/days 1))

        client (:client @connection)
        bucket-name (:status-snapshot-s3-bucket-name env)
        txt-s3-file-path (str "evidence-input/" ymd-string ".txt")]

    (if (.doesObjectExist client bucket-name txt-s3-file-path)
      (log/info "Text log file exists at " txt-s3-file-path ", skipping.")
      (let [file (File/createTempFile ymd-string ".txt")]
        (log/info "Saving log entries for" ymd-string "to temp file")

        (with-open [w (writer file)]
          (retrieve-date-range
            start-date
            end-date
            (:percolator-input-evidence-record-topic env)
            (fn [record]
              (.write w ^String (.value record))
              (.write w "\n"))))

          (log/info "Uploading Evidence Records text file to" txt-s3-file-path)
          (.putObject client bucket-name txt-s3-file-path file)
          (log/info "Done uploading Evidence Records text file.")

          (.delete file)))))

(defn run-backfill
  []
  (log/info "Check for" max-historical-days "days")
  (let [yesterday (clj-time/minus (clj-time/now) (clj-time/days 1))
        days (take max-historical-days (clj-time-periodic/periodic-seq yesterday (clj-time/days -1)))]
    (doseq [day days]
      (ensure-day-evidence-logs day)
      (ensure-day-evidence-records day))))

(defjob daily-schedule-job
  [ctx]
  (log/info "Running daily task...")
  (run-backfill)
  (log/info "Done daily task."))

(defn run-schedule
  "Start schedule to generate daily reports. Block."
  []
  (log/info "Start scheduler")
  (let [s (-> (qs/initialize) qs/start)
        job (qj/build
              (qj/of-type daily-schedule-job)
              (qj/with-identity (qj/key "jobs.noop.1")))
        trigger (qt/build
                  (qt/with-identity (qt/key "triggers.1"))
                  (qt/start-now)
                  (qt/with-schedule (qc/cron-schedule "0 0 1 * * ?")))]
  (qs/schedule s job trigger)))

(defn -main
  [& args]
  (let [command (first args)]
    (condp = command
      "run" (run-backfill)
      "schedule" (run-schedule)
      (log/error "Unrecognized command"))))

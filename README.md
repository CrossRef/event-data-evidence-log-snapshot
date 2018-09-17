# Event Data Evidence Log Snapshot

Snapshots the Evidence Log and Evidence Record inputs into archive files. Runs hourly and daily schedules. Checks a configurable number of days and saves snapshots when they don't exist. The Evidence Log snapshots are performed every hour in to hourly files. The Evidence Record input archiving is performed every day.

Note that the lines are split along the timestamp recorded by Kafka, which may be very slightly different to the timestamp recorded by the log. If you're interested in precise boundaries, fetch an extra day either side of the range in question.

## Evidence Log

Ingests the entire Evidence Log Kafka topic and saves each day's worth into a snapshot in S3.

Saves two Evidence Log snapshots per day, `YYYY-MM-DD.txt` and `YYYY-MM-DD.csv`. The .txt file contains lines of JSON. The CSV file contains the lines expressed as JSON with a header line. The field names (and columns) are:

 - t : timestamp
 - s : service
 - c : component
 - f : facet
 - p : partition
 - v : value
 - e : extra values

## Evidence Records

Saves all Evidence Records for a given day into one text file, one line per Evidence Record JSON.

## To run

### Schedule

This will run and block, scheduling daily updates.

    lein run schedule

### One off

This will run the daily task (i.e. fill any missing logs up to last 10 days).

    lein run run

## Config

The following config keys are required:

    GLOBAL_STATUS_TOPIC
    PERCOLATOR_INPUT_EVIDENCE_RECORD_TOPIC
    GLOBAL_KAFKA_BOOTSTRAP_SERVERS
    STATUS_SNAPSHOT_S3_BUCKET_NAME
    STATUS_SNAPSHOT_S3_REGION_NAME
    STATUS_SNAPSHOT_S3_KEY
    STATUS_SNAPSHOT_S3_SECRET
    STATUS_SNAPSHOT_MAX_DAYS

## License

Copyright © 2018 Crossref

Distributed under the The MIT License (MIT).

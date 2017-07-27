# Event Data Evidence Log Snapshot

Snapshots the Evidence Log into archive files.

Runs daily. Ingests the entire Evidence Log Kafka topic and saves each day's worth into a snapshot in S3.

Saves two snapshots per day, `YYYY-MM-DD.txt` and `YYYY-MM-DD.csv`. The .txt file contains lines of JSON. The CSV file contains the lines expressed as JSON with a header line. The field names (and columns) are:

 - t : timestamp
 - s : service
 - c : component
 - f : facet
 - p : partition
 - v : value
 - e : extra values

Note that the lines are split along the timestamp recorded by Kafka, which may be very slightly different to the timestamp recorded by the log. If you're interested in precise boundaries, fetch an extra day either side of the range in question.

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
    GLOBAL_KAFKA_BOOTSTRAP_SERVERS
    STATUS_SNAPSHOT_S3_BUCKET_NAME
    STATUS_SNAPSHOT_S3_REGION_NAME
    STATUS_SNAPSHOT_S3_KEY
    STATUS_SNAPSHOT_S3_SECRET

## License

Copyright © 2017 Crossref

Distributed under the The MIT License (MIT).

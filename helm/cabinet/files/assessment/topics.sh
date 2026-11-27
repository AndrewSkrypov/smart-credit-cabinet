#!/bin/sh
set -eu

broker=assessment-kafka:9092
bin=/opt/kafka/bin
prefix="alpha.dev.assessment.v1.${ASSESSMENT_GENERATION:?}"
while ! "$bin/kafka-topics.sh" --bootstrap-server "$broker" --list >/dev/null 2>&1; do sleep 2; done
cluster=$("$bin/kafka-metadata-quorum.sh" --bootstrap-server "$broker" describe --status |
    awk '$1 == "ClusterId:" {print $2}')
test "$cluster" = "${ASSESSMENT_CLUSTER_ID:?}"

for direction in commands results; do
    topic=$prefix.$direction
    if test "${ASSESSMENT_BOOTSTRAP_TOPICS:-false}" = true; then
        "$bin/kafka-topics.sh" --bootstrap-server "$broker" --create --if-not-exists \
            --topic "$topic" --partitions 1 --replication-factor 1 \
            --config retention.ms=-1 --config retention.bytes=-1 --config cleanup.policy=delete
    fi
    description=$("$bin/kafka-topics.sh" --bootstrap-server "$broker" --describe --topic "$topic")
    printf '%s\n' "$description" | grep -Eq 'PartitionCount:[[:space:]]*1[[:space:]]'
    printf '%s\n' "$description" | grep -Eq 'ReplicationFactor:[[:space:]]*1[[:space:]]'
    printf '%s\n' "$description" | grep -Eq 'Leader:[[:space:]]*1[[:space:]]'
    printf '%s\n' "$description" | grep -Eq '(^|[ ,])retention\.ms=-1(,|$)'
    printf '%s\n' "$description" | grep -Eq '(^|[ ,])retention\.bytes=-1(,|$)'
    printf '%s\n' "$description" | grep -Eq '(^|[ ,])cleanup\.policy=delete(,[[:alnum:]_.-]+=|$)'
done

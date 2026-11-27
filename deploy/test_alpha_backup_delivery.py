"""Kafka omits uncommitted empty partitions after the last group member leaves."""
from copy import deepcopy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from runtime_backup import alpha_checkpoint, alpha_committed_offsets

GROUP = "alpha-qa-backup"
TOPIC = "alpha.synthetic.backup"
BOUNDS = {"begin": {"0": 0, "1": 0, "2": 0}, "end": {"0": 0, "1": 0, "2": 0}}
HEADER = "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n"


def row(partition, offset, topic=TOPIC, group=GROUP, end=0):
    return f"{group} {topic} {partition} {offset} {end} - - - -\n"


class AlphaBackupOffsetsTest(unittest.TestCase):
    def parse(self, text, bounds=None):
        return alpha_committed_offsets(GROUP, TOPIC, text, bounds or BOUNDS)

    def test_active_empty_assignments_and_stopped_empty_output_preserve_none(self):
        active = HEADER + "".join(row(partition, "-") for partition in range(3))
        self.assertEqual(self.parse(active), {"0": None, "1": None, "2": None})
        for stopped in ("", "\n", HEADER):
            with self.subTest(stopped=stopped):
                self.assertEqual(self.parse(stopped), self.parse(active))

    def test_partial_commit_survives_stop_and_omitted_empty_partitions(self):
        bounds = deepcopy(BOUNDS)
        bounds["end"]["0"] = 7
        active = HEADER + row(0, 7, end=7) + row(1, "-") + row(2, "-")
        stopped = HEADER + row(0, 7, end=7)
        self.assertEqual(self.parse(active, bounds), {"0": 7, "1": None, "2": None})
        self.assertEqual(self.parse(stopped, bounds), self.parse(active, bounds))

    def test_explicit_zero_commit_is_not_changed_to_none(self):
        self.assertEqual(self.parse(HEADER + row(0, 0)), {"0": 0, "1": None, "2": None})

    def test_all_explicit_numeric_commits_are_preserved(self):
        bounds = {"begin": {"0": 3, "1": 0, "2": 1}, "end": {"0": 9, "1": 4, "2": 5}}
        text = HEADER + row(0, 8, end=9) + row(1, 0, end=4) + row(2, 3, end=5)
        self.assertEqual(self.parse(text, bounds), {"0": 8, "1": 0, "2": 3})

    def test_missing_nonempty_or_retained_partition_is_not_inferred(self):
        for boundary in ("begin", "end"):
            bounds = deepcopy(BOUNDS)
            bounds[boundary]["1"] = 1
            with self.subTest(boundary=boundary), self.assertRaisesRegex(RuntimeError, "Неполные offsets"):
                self.parse(HEADER + row(0, 0), bounds)

    def test_unexpected_duplicate_or_malformed_rows_are_rejected(self):
        for malformed in (
            row(0, 0, topic="alpha.synthetic.other"), row(0, 0, group="other"), row(3, 0),
            row(-1, 0), row("01", 0), row(0, "NaN"), row(0, -1), row(0, "0.5"),
            row(0, 0) + row(0, 0), row(0, 0, end="unknown"), "Error: coordinator unavailable\n",
            f"{GROUP} {TOPIC} 0 -\n",
        ):
            with self.subTest(malformed=malformed), self.assertRaisesRegex(RuntimeError, "Не подтверждены offsets"):
                self.parse(HEADER + malformed)

    def test_checkpoint_uses_checked_topic_bounds_and_preserves_transport_errors(self):
        alpha = SimpleNamespace(topic=TOPIC, dlq=TOPIC + ".dlq", group=GROUP, generation="g123")
        deployment = SimpleNamespace(alpha_dev=alpha)
        marker = TOPIC + " " + "a" * 64
        def kafka(_deployment, tool, *args):
            if tool == "kafka-topics":
                return "TopicId: " + "a" * 22 + " PartitionCount: 3 ReplicationFactor: 1"
            if tool == "kafka-get-offsets":
                topic = args[args.index("--topic") + 1]
                return "\n".join(f"{topic}:{partition}:0" for partition in range(3))
            return ""
        with patch("runtime_backup.analytics_owner", return_value=marker), patch("runtime_backup.alpha_outbox", return_value={}), patch("runtime_backup.kafka", side_effect=kafka):
            checkpoint = alpha_checkpoint(deployment)
            self.assertEqual(checkpoint["committed"], {"0": None, "1": None, "2": None})
        with patch("runtime_backup.analytics_owner", return_value=marker), patch("runtime_backup.kafka", side_effect=RuntimeError("CLI transport failed")):
            with self.assertRaisesRegex(RuntimeError, "CLI transport failed"):
                alpha_checkpoint(deployment)


if __name__ == "__main__":
    unittest.main()

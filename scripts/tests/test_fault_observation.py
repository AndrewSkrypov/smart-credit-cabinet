import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("fault_observation", Path(__file__).resolve().parents[2] / "load/fault_observation.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FaultObservationTest(unittest.TestCase):
    def test_starting_process_does_not_release_the_next_drills_writes(self):
        states = iter([{"Running": True, "Health": {"Status": value}}
                       for value in ("starting", "unhealthy", "healthy")])
        elapsed = [0]
        def advance(seconds):
            elapsed[0] += seconds
        result = module.wait_for_container_healthy(lambda: next(states), clock=lambda: elapsed[0], sleep=advance)
        self.assertEqual({"healthy": True, "seconds": 1}, result)

    def test_a_failed_restart_is_not_reported_as_restored(self):
        with self.assertRaisesRegex(AssertionError, "остановился"):
            module.wait_for_container_healthy(lambda: {"Running": False})
        elapsed = [0]
        def advance(seconds):
            elapsed[0] += seconds
        with self.assertRaisesRegex(AssertionError, "не готов"):
            module.wait_for_container_healthy(lambda: {"Running": True, "Health": {"Status": "starting"}},
                                              timeout=1, clock=lambda: elapsed[0], sleep=advance)

    def test_queued_members_are_allowed_to_reach_enrichment_before_assertion(self):
        states = iter([["ENRICHMENT", "SUBMITTED"], ["ENRICHMENT", "SUBMITTED"], ["ENRICHMENT", "ENRICHMENT"]])
        elapsed = [0]
        def advance(seconds):
            elapsed[0] += seconds
        result = module.wait_for_enrichment(lambda: next(states), clock=lambda: elapsed[0], sleep=advance)
        self.assertEqual(["ENRICHMENT", "ENRICHMENT"], result)
        self.assertEqual(0.5, elapsed[0])

    def test_a_stuck_queue_remains_a_failure(self):
        elapsed = [0]
        def advance(seconds):
            elapsed[0] += seconds
        with self.assertRaisesRegex(AssertionError, "SUBMITTED"):
            module.wait_for_enrichment(lambda: ["SUBMITTED"], timeout=1, clock=lambda: elapsed[0], sleep=advance)

    def test_an_unexpected_final_state_is_not_hidden_by_polling(self):
        with self.assertRaises(AssertionError):
            module.wait_for_enrichment(lambda: ["DISBURSED"])

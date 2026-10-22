"""Wait for asynchronous queue admission before observing a cohort's source outage."""
import time


def wait_for_enrichment(read_statuses, timeout=20, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while True:
        statuses = read_statuses()
        assert statuses and all(value in {"SUBMITTED", "ENRICHMENT", "MANUAL_REVIEW"} for value in statuses), statuses
        if "SUBMITTED" not in statuses:
            return statuses
        assert clock() < deadline, f"Не все заявки вышли из очереди SUBMITTED за {timeout} с: {statuses}"
        sleep(0.25)

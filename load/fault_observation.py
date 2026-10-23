"""Wait for asynchronous queue admission before observing a cohort's source outage."""
import time


def wait_for_container_healthy(read_state, timeout=90, clock=time.monotonic, sleep=time.sleep):
    """A started process cannot safely receive the next drill's setup writes yet."""
    started = clock()
    while True:
        state = read_state()
        assert state.get("Running"), "Восстанавливаемый контейнер остановился"
        health = state.get("Health", {}).get("Status")
        if health == "healthy":
            return {"healthy": True, "seconds": clock() - started}
        assert clock() - started < timeout, f"Контейнер не готов за {timeout} с: {health}"
        sleep(0.5)


def wait_for_enrichment(read_statuses, timeout=20, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    while True:
        statuses = read_statuses()
        assert statuses and all(value in {"SUBMITTED", "ENRICHMENT", "MANUAL_REVIEW"} for value in statuses), statuses
        if "SUBMITTED" not in statuses:
            return statuses
        assert clock() < deadline, f"Не все заявки вышли из очереди SUBMITTED за {timeout} с: {statuses}"
        sleep(0.25)

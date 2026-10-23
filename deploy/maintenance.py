"""A shared PostgreSQL session serializes document transfer and quiesced backups."""
import queue
import json
import os
import subprocess
import threading
from uuid import uuid4


def private_checkpoint(path, value):
    """Publish a complete report atomically so a process crash retains a resumable predecessor."""
    temporary = path.with_name(path.name + "." + uuid4().hex + ".tmp")
    descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


class Database:
    """One actual PostgreSQL session owns the maintenance lock until all cleanup finishes."""
    def __init__(self, container, user):
        self.process = subprocess.Popen(
            ["docker", "exec", "-i", container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", "credit"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", bufsize=1,
        )
        self.lines = queue.Queue()

        def read():
            for line in self.process.stdout:
                self.lines.put(line.rstrip("\r\n"))
            self.lines.put(None)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def sql(self, statement):
        marker = "storage_" + uuid4().hex
        self.process.stdin.write(statement + "\n\\echo " + marker + "\n")
        self.process.stdin.flush()
        values = []
        while True:
            try:
                line = self.lines.get(timeout=30)
            except queue.Empty:
                raise RuntimeError("PostgreSQL maintenance statement timeout") from None
            if line is None:
                raise RuntimeError("PostgreSQL maintenance statement failed")
            if line == marker:
                return "\n".join(values)
            values.append(line)

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.reader.join(timeout=2)

"""Monotonic durations only: no prompts, audio, credentials or wall-clock subtraction."""
from contextlib import contextmanager
from time import perf_counter


class StageTimings:
    def __init__(self):
        self.started = perf_counter()
        self.values = {}

    @contextmanager
    def measure(self, name):
        start = perf_counter()
        try:
            yield
        finally:
            self.values[name] = round((perf_counter() - start) * 1000, 3)

    def finish(self):
        return {**self.values, 'total_ms': round((perf_counter() - self.started) * 1000, 3)}

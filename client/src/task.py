import threading
import time
from typing import Any, Callable, Optional


class BackgroundTask:
    """
    A simple background task that runs a function in a separate thread.

    It will call the function at the specified rate, and stop when the stop event is set.
    """

    def __init__(self, work_fn: Callable):
        self.work_fn = work_fn
        self.work_rate_seconds = 1
        self.stop = threading.Event()
        self.thread: Optional[threading.Thread] = None

    def start(self, on_stop: Callable[[], None] | None = None, fn_args: list[Any] = []):
        if self.thread and self.thread.is_alive():
            return
        self.stop.clear()
        self.thread = threading.Thread(target=self.run, args=(on_stop, fn_args))
        self.thread.daemon = True
        self.thread.start()

    def run(self, on_stop: Callable[[], None] | None = None, fn_args: list[Any] = []):
        try:
            while not self.stop.is_set():
                self.work_fn(*fn_args)
                time.sleep(self.work_rate_seconds)
        finally:
            if on_stop:
                on_stop()

    def is_running(self):
        return self.thread and self.thread.is_alive()

    def set_work_rate(self, work_rate_seconds: float):
        if work_rate_seconds < 0.1:
            raise ValueError("Work rate must be greater than 0.1 seconds")
        self.work_rate_seconds = work_rate_seconds

    def request_stop(self, timeout: float = 1):
        self.stop.set()
        if self.thread and self.thread.is_alive():
            print(f"Stopping {self.work_fn.__name__}")
            self.thread.join(timeout=timeout)

import datetime
from pathlib import Path
import signal
from pydantic import BaseModel
import queue
import time
import grpc
from billing_client import BillingClient
from claim_util import json_to_claim
import json
import sys

from generated import billing_service_pb2, payer_claim_pb2

import threading
from typing import Any, Callable, Literal, Optional, TextIO, Union


class BackgroundTask:
    def __init__(self, work_fn: Callable):
        self.work_fn = work_fn
        self.work_rate_seconds = 1
        self.stop = threading.Event()
        self.thread: Optional[threading.Thread] = None

    def start(self, on_stop: Callable[[], None] | None = None, fn_args: list[Any] = []):
        print(f"Starting 1 {self.work_fn.__name__}")
        if self.thread and self.thread.is_alive():
            return
        self.stop.clear()
        self.thread = threading.Thread(target=self.run, args=(on_stop, fn_args))
        self.thread.daemon = True
        self.thread.start()

    def run(self, on_stop: Callable[[], None] | None = None, fn_args: list[Any] = []):
        print(f"Starting 2 {self.work_fn.__name__}")
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
        if work_rate_seconds <= 0.1:
            raise ValueError("Work rate must be greater than 0.1 seconds")
        self.work_rate_seconds = work_rate_seconds

    def request_stop(self, timeout: float = 1):
        self.stop.set()
        if self.thread and self.thread.is_alive():
            print(f"Stopping {self.work_fn.__name__}")
            self.thread.join(timeout=timeout)


class DashboardState(BaseModel):
    last_updated_time: datetime.datetime
    ar_by_payer: dict[str, float]
    ar_by_patient: dict[str, float]


class AppState:
    def __init__(self):
        self.claim_submit_rate_seconds = 1  # How fast to drain the claim queue
        self.dashboard_refresh_rate_seconds = 1
        self.submit_claim_responses: dict[
            str, Union[billing_service_pb2.SubmitClaimResponse, grpc.RpcError]
        ] = {}
        self.dashboard_state: Optional[DashboardState] = None


class App:
    def __init__(self):
        self.active_ui: Optional[Literal["main_menu", "submit_claims", "dashboard"]] = (
            None
        )
        self.client = BillingClient()
        self.submit_queue: queue.Queue[payer_claim_pb2.PayerClaim] = queue.Queue()
        self.submit_message_queue: queue.Queue[str] = queue.Queue()
        self.state = AppState()
        self.submit_task = BackgroundTask(self._submit_claim)
        self.read_claims_from_file_task = BackgroundTask(self._read_claims_from_file)
        self.generate_random_claims_task = BackgroundTask(self._generate_random_claims)
        self.refresh_dashboard_task = BackgroundTask(self._refresh_dashboard)

    def on_signal(self, signum, frame):
        if self.active_ui == "main_menu":
            self.shutdown()
        else:
            self.active_ui = "main_menu"

    def _print_menu(self):
        print("\nMain menu\n")
        print(" 1  Submit claims from file")
        print(" 2  Stop submitting claims from file")
        print(" 3  Generate random claims")
        print(" 4  Stop generating random claims")
        print(" 5  View claim submissions")
        print(" 6  Launch Dashboard")
        print(" 0  Quit")

    def start(self):
        self.submit_task.start()  # For now always keep submit task running
        while True:
            self.active_ui = "main_menu"
            self._print_menu()
            choice = input("Select an option: ").strip()
            if choice == "1":
                self.submit_from_file()
            elif choice == "2":
                self.stop_submit_from_file()
            elif choice == "3":
                self.generate_claims()
            elif choice == "4":
                self.stop_generate_claims()
            elif choice == "5":
                self.show_submit_messages()
            elif choice == "6":
                self.show_dashboard()
            elif choice == "0":
                self.shutdown()
                break
            else:
                print("✖ invalid choice")

    def shutdown(self):
        for task in [
            self.submit_task,
            self.read_claims_from_file_task,
            self.generate_random_claims_task,
            self.refresh_dashboard_task,
        ]:
            task.stop.set()
            if task.thread and task.thread.is_alive():
                print(f"Stopping {task.work_fn.__name__}")
                task.thread.join(timeout=1)
        self.client.channel.close()

    def show_submit_messages(self):
        self.active_ui = "submit_claims"
        first = True
        while self.active_ui == "submit_claims":
            messages = []
            # By draining the queue all at once, we avoid a flickering effect when redrawing the Ctrl+C prompt.
            # (This is because we end up re-drawing the screen less often.)
            while True:
                try:
                    message = self.submit_message_queue.get(timeout=0.2)
                    messages.append(message)
                except queue.Empty:
                    break
            if len(messages) == 0:
                continue
            messages.append("Press Ctrl+C to return to main menu.")
            message = "\n".join(messages)
            if not first:
                sys.stdout.write("\033[F\033[K")  # move up 1 line, wipe it
            else:
                first = False
            print(message)
            sys.stdout.flush()  # immediate redraw

    def _submit_claim(self):
        try:
            claim = self.submit_queue.get(timeout=0.2)
        except queue.Empty:
            return False
        self.submit_message_queue.put(f"Submitting claim {claim.claim_id}")
        resp = self.client.submit_claim(claim)
        self.submit_message_queue.put(f"Response: {resp}")
        self.state.submit_claim_responses[claim.claim_id] = resp
        self.submit_queue.task_done()
        return False

    def _read_claims_from_file(self, file_handle: TextIO):
        try:
            line = file_handle.readline()
            if not line.strip():
                return
            try:
                claim = json_to_claim(json.loads(line))
                self.submit_message_queue.put(f"Read claim from file: {claim.claim_id}")
                self.submit_queue.put(claim)
            except Exception as e:
                self.submit_message_queue.put(f"⚠️  skipping malformed line: {e}")
        except Exception as e:
            self.submit_message_queue.put(f"❌ Error reading file: {e}")

    def _generate_random_claims(self):
        pass

    def _refresh_dashboard(self):
        pass

    def submit_from_file(self):
        if self.read_claims_from_file_task.is_running():
            print("Already loading claims from file, would you like to stop it? (y/n)")
            if input().strip().lower() == "y":
                self.stop_submit_from_file()
            return
        path = input("Path to JSON-lines file: ").strip()
        file_path = Path(path)
        if not file_path.exists():
            print("✖ file not found")
            return
        rate_s = input("Seconds between claims: ").strip() or "1"
        file_handle = open(file_path)
        self.read_claims_from_file_task.set_work_rate(float(rate_s))

        def on_stop():
            file_handle.close()

        self.read_claims_from_file_task.start(on_stop, [file_handle])
        print("✔ started loading claims in background")

    def stop_submit_from_file(self):
        if self.read_claims_from_file_task.is_running():
            self.read_claims_from_file_task.request_stop()
            print("✔ stopped loading claims from file")
        else:
            print("No claims are being loaded from file")

    def generate_claims(self):
        pass

    def stop_generate_claims(self):
        if self.generate_random_claims_task.is_running():
            self.generate_random_claims_task.request_stop()
            print("✔ stopped generating random claims")
        else:
            print("No claims are being generated")

    def show_dashboard(self):
        pass


def main():
    app = App()
    signal.signal(signal.SIGINT, app.on_signal)
    app.start()


if __name__ == "__main__":
    main()

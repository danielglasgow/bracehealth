import datetime
from pathlib import Path
import signal
import queue
import time
import grpc
from dashboard import (
    render_dashboard,
    DashboardState,
    SLOW_BUCKETS,
    FAST_BUCKETS,
    LIGHTNING_BUCKETS,
)
from billing_client import BillingClient
from task import BackgroundTask
from claim_util import generate_random_claim, json_to_claim
import json
import sys
import traceback

from generated import billing_service_pb2, payer_claim_pb2

from typing import Literal, Optional, TextIO, Union

import os


class App:
    def __init__(self):
        self.active_ui: Optional[Literal["main_menu", "submit_claims", "dashboard"]] = (
            None
        )
        self.client = BillingClient()
        self.submit_queue: queue.Queue[payer_claim_pb2.PayerClaim] = queue.Queue()
        self.submit_message_queue: queue.Queue[str] = queue.Queue()
        self.submit_task = BackgroundTask(self._submit_claim)
        self.read_claims_from_file_task = BackgroundTask(self._read_claims_from_file)
        self.generate_random_claims_task = BackgroundTask(self._generate_random_claims)
        self.refresh_dashboard_task = BackgroundTask(self._refresh_dashboard)
        self.file_path: Optional[Path] = None
        self.claims_submitted: int = 0
        self.submit_claim_responses: dict[
            str, Union[billing_service_pb2.SubmitClaimResponse, grpc.RpcError]
        ] = {}
        self.dashboard_state: Optional[DashboardState] = None

    def on_signal(self, signum, frame):
        if self.active_ui == "main_menu":
            print()
            self.shutdown()
            sys.exit(0)
        else:
            self.active_ui = "main_menu"

    def _print_menu(self):
        os.system("clear" if os.name == "posix" else "cls")
        print(
            "BraceHealth Billing CLI\n"
            "────────────────────────\n"
            f"Server: {self.client.target}\n"
        )
        print(
            "Claim submission: ",
            "Active" if self.submit_queue.qsize() > 0 else "Inactive",
        )
        print(
            "Read claims from file: ",
            (
                f"Active ({self.file_path})"
                if self.read_claims_from_file_task.is_running()
                else "Inactive"
            ),
        )
        print(
            "Generate random claims: ",
            "Active" if self.generate_random_claims_task.is_running() else "Inactive",
        )
        print(
            "Dashboard data fetch: ",
            "Active" if self.refresh_dashboard_task.is_running() else "Inactive",
        )
        print("\nMain menu\n")
        print(" 1  Submit claims from file")
        print(" 2  Stop submitting claims from file")
        print(" 3  Generate random claims")
        print(" 4  Stop generating random claims")
        print(" 5  View claim submissions")
        print(" 6  Launch Dashboard")
        print(" 0  Quit")
        print()

    def start(self):
        self.submit_task.set_work_rate(
            0.1  # Drain claims as soon as their posted but avoid busy waiting
        )
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
        self.submit_claim_responses[claim.claim_id] = resp
        self.submit_queue.task_done()
        return False

    def _read_claims_from_file(self, file_handle: TextIO):
        line = file_handle.readline()
        claim = json_to_claim(json.loads(line))
        self.submit_message_queue.put(f"Read claim from file: {claim.claim_id}")
        self.submit_queue.put(claim)

    def _generate_random_claims(self):
        claim = generate_random_claim()
        self.submit_message_queue.put(f"Generated claim: {claim['claim_id']}")
        self.submit_queue.put(json_to_claim(claim))

    def _refresh_dashboard(self):
        self.dashboard_state = DashboardState(
            last_updated_time=datetime.datetime.now(),
            last_patients_ar_response=self.client.ar_by_patient(),
            last_aging_ar_response=self.client.ar_by_payer(
                [(s, e) for _, s, e in self.dashboard_buckets]
            ),
        )

    def submit_from_file(self):
        if self.read_claims_from_file_task.is_running():
            print("Already loading claims from file, would you like to stop it? (y/n)")
            if input().strip().lower() == "y":
                self.stop_submit_from_file()
            return
        path = input("Path (absolute or relative) to JSON-lines file: ").strip()
        file_path = Path(path).resolve()
        if not file_path.exists():
            print("✖ file not found")
            return
        rate_s = input("Seconds between claims: ").strip() or "1"
        file_handle = open(file_path)
        self.read_claims_from_file_task.set_work_rate(float(rate_s))
        self.file_path = file_path

        def on_stop():
            file_handle.close()
            self.file_path = None

        self.read_claims_from_file_task.start(on_stop, [file_handle])
        print("✔ started loading claims in background")

    def stop_submit_from_file(self):
        if self.read_claims_from_file_task.is_running():
            self.read_claims_from_file_task.request_stop()
            print("✔ stopped loading claims from file")
        else:
            print("No claims are being loaded from file")

    def generate_claims(self):
        rate_s = input("Seconds between claims: ").strip() or "1"
        rate = float(rate_s)
        self.generate_random_claims_task.set_work_rate(rate)
        self.generate_random_claims_task.start()

    def stop_generate_claims(self):
        if self.generate_random_claims_task.is_running():
            self.generate_random_claims_task.request_stop()
            print("✔ stopped generating random claims")
        else:
            print("No claims are being generated")

    def show_dashboard(self):
        rate_s = input("Seconds between dashboard refreshes: ").strip() or "1"
        rate = float(rate_s)
        self.active_ui = "dashboard"
        print("Dashboard AR bucket modes")
        print(" 1  Slow (0-1min, 1-2min, 2-3min, 3min+)")
        print(" 2  Fast (0-10s, 10-20s, 20-30s, 30s+)")
        print(" 3  Lightning (0-1s, 1-2s, 2-3s, 3s+)")
        choice = input("Select a bucket mode: ").strip()
        if choice == "1":
            self.dashboard_buckets = SLOW_BUCKETS
        elif choice == "2":
            self.dashboard_buckets = FAST_BUCKETS
        elif choice == "3":
            self.dashboard_buckets = LIGHTNING_BUCKETS
        else:
            self.dashboard_buckets = SLOW_BUCKETS
        self.refresh_dashboard_task.set_work_rate(rate)
        self.refresh_dashboard_task.start()
        print("Waiting for dashboard data", end="", flush=True)
        while self.active_ui == "dashboard":
            if self.dashboard_state is None:
                print(".", end="", flush=True)
            else:
                render_dashboard(
                    rate,
                    self.dashboard_state,
                    self.dashboard_buckets,
                    self.submit_claim_responses,
                )
                print("Ctrl+C to return to main menu")
            time.sleep(0.1)  # Repaint every 100ms
        self.refresh_dashboard_task.request_stop()


def main():
    app = App()
    signal.signal(signal.SIGINT, app.on_signal)
    try:
        app.start()
    except Exception as e:
        print("Error occurred:")
        traceback.print_exc()
        app.shutdown()
        sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
billing_cli.py – interactive client for BraceHealth BillingService
==================================================================

↦  Requirements
    - Python ≥3.9
    - grpcio           (pip install grpcio)
    - generated gRPC modules in PYTHONPATH:
        billing_service_pb2, billing_service_pb2_grpc,
        payer_claim_pb2, common_pb2

↦  Key features
    1) Submit claims from a JSON-lines file at a given rate
    2) Generate random claims at a given rate
    3) View A/R by payer (custom buckets, optional payer filter)
    4) View A/R by patient
    5) View claims for a specific patient
    6) Pay (all or part of) a claim
    7) Real-time dashboard that auto-refreshes every 5 s

Run it, follow the on-screen prompts, press ⏎ to accept defaults, or
type "back" at almost any prompt to return to the main menu.
"""


from __future__ import annotations

import json
import os
import queue
import random
import signal
import threading
import time
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Dict, List, Optional, Sequence, Tuple

import grpc

# ─────────────────────────── generated stubs ────────────────────────── #
from generated import (
    billing_service_pb2 as bs_pb2,
    billing_service_pb2_grpc as bs_pb2_grpc,
    payer_claim_pb2 as pc_pb2,
    common_pb2,
)

# ─────────────────────────── constants ──────────────────────────────── #
SERVER_ADDR = os.getenv("BILLING_SERVER", "localhost:9090")
DASH_INTERVAL = 5.0  # seconds
CLS = "cls" if os.name == "nt" else "clear"

# Default four aging buckets (seconds ago)
DEFAULT_BUCKETS: List[Tuple[str, Optional[int], Optional[int]]] = [
    ("0-1 min", 60, 0),
    ("1-2 min", 120, 60),
    ("2-3 min", 180, 120),
    ("3+ min", None, 180),
]

CURRENCY_FMT = "${:,.2f}"

# ─────────────────────────── helpers ────────────────────────────────── #


def _currency_from_float(amt: float) -> common_pb2.CurrencyValue:
    """Convert float dollars to CurrencyValue (2-decimal fixed-point)."""
    whole = int(amt)
    decimals = int(round((amt - whole) * 100))
    return common_pb2.CurrencyValue(whole_amount=whole, decimal_amount=decimals)


def _currency_to_float(cv: common_pb2.CurrencyValue) -> float:
    return cv.whole_amount + cv.decimal_amount / 100.0


def _fmt_cv(cv: common_pb2.CurrencyValue) -> str:  # pretty printer
    return CURRENCY_FMT.format(_currency_to_float(cv))


def _clear():
    os.system(CLS)


def _input(prompt: str) -> str:
    try:
        return input(prompt)
    except EOFError:
        # user hit Ctrl-D
        print()
        return "back"


def _safe_int(s: str, default: int) -> int:
    try:
        return int(s)
    except (ValueError, TypeError):
        return default


# ─────────────────────────── gRPC façade ────────────────────────────── #


class BillingClient:
    def __init__(self, target: str = SERVER_ADDR):
        self.channel = grpc.insecure_channel(target)
        self.stub = bs_pb2_grpc.BillingServiceStub(self.channel)

    # ---------- claim submission ---------- #

    def submit_claim(self, claim: pc_pb2.PayerClaim) -> bs_pb2.SubmitClaimResponse:
        return self.stub.submitClaim(bs_pb2.SubmitClaimRequest(claim=claim), timeout=5)

    # ---------- patient + AR queries ---------- #

    def ar_by_payer(
        self,
        buckets: Sequence[Tuple[Optional[int], Optional[int]]],
        payer_ids: Sequence[int] | None = None,
    ) -> bs_pb2.GetPayerAccountsReceivableResponse:
        req = bs_pb2.GetPayerAccountsReceivableRequest()
        for start, end in buckets:
            b = req.bucket.add()
            if start is not None:
                b.start_seconds_ago = start
            if end is not None:
                b.end_seconds_ago = end
        if payer_ids:
            req.payer_filter.extend(payer_ids)
        return self.stub.getPayerAccountsReceivable(req, timeout=3)

    def ar_by_patient(
        self, patient_ids: Sequence[str] | None = None
    ) -> bs_pb2.GetPatientAccountsReceivableResponse:
        req = bs_pb2.GetPatientAccountsReceivableRequest()
        if patient_ids:
            req.patient_filter.extend(patient_ids)
        return self.stub.getPatientAccountsReceivable(req, timeout=3)

    def patient_claims(self, patient_id: str) -> bs_pb2.GetPatientClaimsResponse | None:
        req = bs_pb2.GetPatientClaimsRequest(patient_filter=patient_id)
        resp = self.stub.getPatientClaims(req, timeout=3)
        if (
            resp.error
            != bs_pb2.GetPatientClaimsResponse.GET_PATIENT_CLAIM_ERROR_UNSPECIFIED
        ):
            print("⚠️ ", resp.error)
            return None
        return resp

    # ---------- payments ---------- #

    def pay_claim(
        self, claim_id: str, amt: common_pb2.CurrencyValue
    ) -> bs_pb2.SubmitPatientPaymentResponse:
        req = bs_pb2.SubmitPatientPaymentRequest(claim_id=claim_id, amount=amt)
        return self.stub.submitPatientPayment(req, timeout=5)

    # ---------- (future) patient directory ---------- #

    def list_patients(self) -> List[str]:
        """
        Placeholder until `getPatients` RPC exists.  For now, derive patient IDs
        from `getPatientAccountsReceivable`.
        """
        resp = self.ar_by_patient()
        ids: List[str] = []
        for row in resp.row:
            ids.append(
                f"{row.patient.first_name.lower()}_{row.patient.last_name.lower()}_{row.patient.dob.lower()}"
            )
        return sorted(set(ids))


# ─────────────────────────── random claim generator ────────────────── #


def _rand_claim(seq: int) -> pc_pb2.PayerClaim:
    """Produce a deterministic-ish random claim (ok for demos)."""
    claim_id = f"CLM{seq:06d}"
    patient_first = random.choice(["Alice", "Bob", "Carol", "Dave"])
    patient_last = random.choice(["Smith", "Johnson", "Lee", "Brown"])
    dob = random.choice(["1980-01-01", "1990-05-10", "1975-12-15", "2000-07-04"])

    insurance = pc_pb2.Insurance(
        payer_id=random.choice(
            [
                pc_pb2.PayerId.MEDICARE,
                pc_pb2.PayerId.UNITED_HEALTH_GROUP,
                pc_pb2.PayerId.ANTHEM,
            ]
        ),
        patient_member_id=f"ID{random.randint(1000,9999)}",
    )
    patient = pc_pb2.Patient(
        first_name=patient_first,
        last_name=patient_last,
        email=f"{patient_first.lower()}.{patient_last.lower()}@example.com",
        gender=random.choice([pc_pb2.Gender.M, pc_pb2.Gender.F]),
        dob=dob,
        address=pc_pb2.Address(
            street="123 Main", city="Boston", state="MA", zip="02115"
        ),
    )
    organization = pc_pb2.Organization(name="Acme Clinic")
    provider = pc_pb2.RenderingProvider(
        first_name="Dr", last_name="Who", npi="1234567890"
    )
    service_line = pc_pb2.ServiceLine(
        service_line_id="SL1",
        procedure_code="99213",
        details="Office visit",
        charge=_currency_from_float(random.uniform(50, 300)),
    )
    return pc_pb2.PayerClaim(
        claim_id=claim_id,
        place_of_service_code=11,
        insurance=insurance,
        patient=patient,
        organization=organization,
        rendering_provider=provider,
        service_lines=[service_line],
    )


# ─────────────────────────── background workers ────────────────────── #


def _submit_worker(
    client: BillingClient,
    q: "queue.Queue[pc_pb2.PayerClaim]",
    stats: SimpleNamespace,
    stop: threading.Event,
):
    """Consumes claim messages from `q` and submits them until `stop` is set."""
    while not stop.is_set():
        try:
            claim = q.get(timeout=0.2)
        except queue.Empty:
            continue
        resp = client.submit_claim(claim)
        stats.last_claim_id = claim.claim_id
        stats.count += 1
        if resp.result != bs_pb2.SubmitClaimResponse.SUBMIT_CLAIM_RESULT_SUCCESS:
            print("❌  submission failed:", resp.result)
        q.task_done()


def _load_claims_worker(
    file_path: Path,
    q: "queue.Queue[pc_pb2.PayerClaim]",
    rate: float,
    stats: SimpleNamespace,
    stop: threading.Event,
):
    """Loads claims from file and adds them to the queue at the specified rate."""
    try:
        with file_path.open() as fh:
            for line in fh:
                if stop.is_set():
                    break
                if not line.strip():
                    continue
                try:
                    j = json.loads(line)
                    claim = _json_to_claim(j)
                    q.put(claim)
                    time.sleep(rate)
                except Exception as e:
                    print("⚠️  skipping malformed line:", e)
                    continue
    except Exception as e:
        print(f"❌ Error reading file: {e}")
    finally:
        stats.active = False


def _generate_claims_worker(
    q: "queue.Queue[pc_pb2.PayerClaim]",
    rate: float,
    num_claims: int,
    stats: SimpleNamespace,
    stop: threading.Event,
):
    """Generates random claims and adds them to the queue at the specified rate."""
    try:
        seq = 0
        while not stop.is_set() and (num_claims < 0 or seq < num_claims):
            q.put(_rand_claim(seq))
            seq += 1
            time.sleep(rate)
    except Exception as e:
        print(f"❌ Error generating claims: {e}")
    finally:
        stats.active = False


def _dashboard_worker(
    client: BillingClient,
    stats: SimpleNamespace,
    stop: threading.Event,
    refresh_rate: float = DASH_INTERVAL,
):
    """Refreshes AR + patient balances every refresh_rate seconds."""
    buckets = DEFAULT_BUCKETS  # reuse defaults
    while not stop.is_set():
        try:
            ar_payer = client.ar_by_payer([(s, e) for _, s, e in buckets])
            ar_patient = client.ar_by_patient()
        except grpc.RpcError as e:
            print("gRPC error in dashboard:", e)
            time.sleep(refresh_rate)
            continue

        _clear()
        print(f"BraceHealth Billing Dashboard")
        print("─" * 72)
        print(f"Refresh rate: {refresh_rate:.1f}s (press 'r' to change)")
        print(f"Last updated: {datetime.now():%Y-%m-%d %H:%M:%S}")
        # Claim submission status
        if stats.active:
            print(
                f"Submitting claims…  total sent: {stats.count}  "
                f"last_claim_id: {stats.last_claim_id}"
            )
        else:
            print("Claim submission: inactive")
        print()

        # AR by payer
        def fmt_row(row):
            total = sum(_currency_to_float(bv.amount) for bv in row.bucket_value)
            return f"{row.payer_name:<20}  {CURRENCY_FMT.format(total):>12}"

        print("Accounts Receivable by payer:")
        for r in ar_payer.row:
            print("  " + fmt_row(r))
        print()

        # Patient balances (top 8)
        print("Top patient balances:")
        rows = sorted(
            ar_patient.row,
            key=lambda r: _currency_to_float(r.balance.outstanding_copay)
            + _currency_to_float(r.balance.outstanding_coinsurance)
            + _currency_to_float(r.balance.outstanding_deductible),
            reverse=True,
        )[:8]
        for r in rows:
            bal = r.balance
            total = (
                _currency_to_float(bal.outstanding_copay)
                + _currency_to_float(bal.outstanding_coinsurance)
                + _currency_to_float(bal.outstanding_deductible)
            )
            name = f"{r.patient.first_name} {r.patient.last_name}"
            print(f"  {name:<25} {CURRENCY_FMT.format(total):>10}")
        print("\n(Enter 0 to stop dashboard, 'r' to change refresh rate)")
        # Sleep in small increments to allow interupt / rejoining this thread
        for _ in range(int(refresh_rate / 0.1)):
            if stop.is_set():
                break
            time.sleep(0.1)


# ─────────────────────────── main menu logic ───────────────────────── #


class CLI:
    def __init__(self):
        self.client = BillingClient()
        self.submit_q: "queue.Queue[pc_pb2.PayerClaim]" = queue.Queue()
        self.submit_stop = threading.Event()
        self.submit_stats = SimpleNamespace(active=False, count=0, last_claim_id="-")
        self.submit_thread: Optional[threading.Thread] = None
        self.dash_refresh_rate = DASH_INTERVAL  # Default refresh rate
        self.load_thread: Optional[threading.Thread] = None
        self.generate_thread: Optional[threading.Thread] = None
        self.load_stop = threading.Event()
        self.generate_stop = threading.Event()

    # ---------- menu ---------- #

    def _print_menu(self):
        print("\nMain menu\n")
        print(
            f" 1  {'Stop loading claims' if self.load_thread and self.load_thread.is_alive() else 'Load claims from file'}"
        )
        print(
            f" 2  {'Stop generating claims' if self.generate_thread and self.generate_thread.is_alive() else 'Generate random claims'}"
        )
        print(" 3  View AR by payer")
        print(" 4  View AR by patient")
        print(" 5  View patient claims")
        print(" 6  Pay a claim")
        print(" 7  Launch Dashboard")
        print(" 0  Quit")

    def start(self):
        self._banner()
        while True:
            if self.dashboard_is_active():
                choice = _input("").strip()
                if choice == "0":
                    self.toggle_dashboard()
                elif choice == "r":
                    self.toggle_dashboard()
                    self.configure_dashboard_refresh_rate()
            else:
                self._print_menu()
                choice = _input("Select an option: ").strip()
                if choice == "1":
                    if self.load_thread and self.load_thread.is_alive():
                        self.stop_loading_claims()
                    else:
                        self.submit_from_file()
                elif choice == "2":
                    if self.generate_thread and self.generate_thread.is_alive():
                        self.stop_generating_claims()
                    else:
                        self.generate_claims()
                elif choice == "3":
                    self.show_ar_by_payer()
                elif choice == "4":
                    self.show_ar_by_patient()
                elif choice == "5":
                    self.show_patient_claims()
                elif choice == "6":
                    self.pay_claim()
                elif choice == "7":
                    self.toggle_dashboard()
                elif choice == "0" or choice.lower() == "quit":
                    self.shutdown()
                    break
                else:
                    print("✖ invalid choice")

    # ---------- claim submission ---------- #

    def _ensure_submit_thread(self):
        if self.submit_thread and self.submit_thread.is_alive():
            return
        self.submit_stop.clear()
        self.submit_thread = threading.Thread(
            target=_submit_worker,
            kwargs=dict(
                client=self.client,
                q=self.submit_q,
                stats=self.submit_stats,
                stop=self.submit_stop,
            ),
            daemon=True,
        )
        self.submit_thread.start()
        self.submit_stats.active = True

    def submit_from_file(self):
        path = _input("Path to JSON-lines file: ").strip()
        if path.lower() == "back":
            return
        fp = Path(path)
        if not fp.exists():
            print("✖ file not found")
            return
        rate_s = _input("Seconds between claims [1]: ").strip() or "1"
        rate = float(rate_s)
        self._ensure_submit_thread()
        self.load_stop = threading.Event()
        self.load_thread = threading.Thread(
            target=_load_claims_worker,
            kwargs=dict(
                file_path=fp,
                q=self.submit_q,
                rate=rate,
                stats=self.submit_stats,
                stop=self.load_stop,
            ),
            daemon=True,
        )
        self.submit_stats.active = True
        self.load_thread.start()
        print("✔ started loading claims in background")
        self.toggle_dashboard()

    def generate_claims(self):
        num_s = _input("How many claims to generate [∞]: ").strip() or "-1"
        num = int(num_s)
        rate_s = _input("Seconds between claims [0.5]: ").strip() or "0.5"
        rate = float(rate_s)

        self._ensure_submit_thread()
        self.generate_stop = threading.Event()
        self.generate_thread = threading.Thread(
            target=_generate_claims_worker,
            kwargs=dict(
                q=self.submit_q,
                rate=rate,
                num_claims=num,
                stats=self.submit_stats,
                stop=self.generate_stop,
            ),
            daemon=True,
        )
        self.submit_stats.active = True
        self.generate_thread.start()
        print("✔ started generating claims in background")
        self.toggle_dashboard()

    # ---------- queries ---------- #

    def show_ar_by_payer(self):
        print("🏦  Accounts Receivable aging buckets (seconds ago)")
        buckets: List[Tuple[int | None, int | None]] = []
        for label, start, end in DEFAULT_BUCKETS:
            inp = _input(f"Use bucket {label} ({start},{end})? [Y/n]: ").strip().lower()
            if inp in ("", "y", "yes"):
                buckets.append((start, end))
        payer_filter = _input("Comma-sep payer IDs to filter (blank = all): ").strip()
        payer_ids = [
            int(p.strip()) for p in payer_filter.split(",") if p.strip()
        ] or None
        resp = self.client.ar_by_payer(buckets, payer_ids)
        print("\nPAYER                   | TOTAL")
        print("------------------------|-------------")
        for row in resp.row:
            tot = sum(_currency_to_float(bv.amount) for bv in row.bucket_value)
            print(f"{row.payer_name:<24}| {CURRENCY_FMT.format(tot):>11}")

    def show_ar_by_patient(self):
        resp = self.client.ar_by_patient()
        hdr = f"{'PATIENT':<26} {'COPAY':>9} {'COINS.':>9} {'DEDUCT.':>9}"
        print(hdr)
        print("-" * len(hdr))
        for r in resp.row:
            bal = r.balance
            print(
                f"{r.patient.first_name} {r.patient.last_name:<24} "
                f"{_fmt_cv(bal.outstanding_copay):>9} "
                f"{_fmt_cv(bal.outstanding_coinsurance):>9} "
                f"{_fmt_cv(bal.outstanding_deductible):>9}"
            )

    def show_patient_claims(self):
        """Show claims for a selected patient."""
        # Get list of patients
        patients = self.client.list_patients()
        if not patients:
            print("No patients found")
            return

        # Show patient list
        print("\nSelect a patient:")
        for i, patient_id in enumerate(patients, 1):
            first, last, dob = patient_id.split("_")
            print(f"{i:2d}  {first} {last} (DOB: {dob})")

        # Get selection
        choice = _input("\nSelect patient number (or 'back'): ").strip()
        if choice.lower() == "back":
            return

        try:
            idx = int(choice) - 1
            if idx < 0 or idx >= len(patients):
                print("✖ invalid selection")
                return
            patient_id = patients[idx]
        except ValueError:
            print("✖ invalid number")
            return

        # Show claims for selected patient
        print(f"\nClaims for {patient_id}:")
        resp = self.client.patient_claims(patient_id)
        if not resp:
            return
        print("\nCLAIM ID     | STATUS                | BALANCE")
        print("-------------|-----------------------|-------------")
        for row in resp.row:
            bal = row.balance
            total = (
                _currency_to_float(bal.outstanding_copay)
                + _currency_to_float(bal.outstanding_coinsurance)
                + _currency_to_float(bal.outstanding_deductible)
            )
            print(
                f"{row.claim_id:<12}| {row.status:<21}| {CURRENCY_FMT.format(total):>11}"
            )

    # ---------- payments ---------- #

    def pay_claim(self):
        claim_id = _input("Claim ID: ").strip()
        amt_s = _input("Amount (blank = pay full balance): ").strip()
        if not claim_id:
            return
        if amt_s:
            amt = float(amt_s)
        else:
            # Need to ask server for balance
            print("→ querying current balance…")
            resp = self.client.patient_claims("")  # crude: ask for all
            bal_found = None
            if resp:
                for row in resp.row:
                    if row.claim_id == claim_id:
                        b = row.balance
                        bal_found = (
                            _currency_to_float(b.outstanding_copay)
                            + _currency_to_float(b.outstanding_coinsurance)
                            + _currency_to_float(b.outstanding_deductible)
                        )
                        break
            if bal_found is None:
                print("✖ claim not found – specify amount manually")
                return
            amt = bal_found
        cv = _currency_from_float(amt)
        resp = self.client.pay_claim(claim_id, cv)
        print("Server response:", resp.result)

    # ---------- dashboard ---------- #

    def configure_dashboard_refresh_rate(self):
        """Configure the dashboard refresh rate."""
        rate_s = _input(
            f"New refresh rate in seconds [{self.dash_refresh_rate}]: "
        ).strip()
        if rate_s:
            try:
                new_rate = float(rate_s)
                if new_rate < 1:
                    print("⚠️  Rate too low, minimum is 1 second")
                    return
                self.dash_refresh_rate = new_rate
                self.toggle_dashboard()
            except ValueError:
                print("⚠️  Invalid number")

    def toggle_dashboard(self):
        if hasattr(self, "dash_thread") and self.dash_thread.is_alive():
            self.dash_stop.set()
            self.dash_thread.join()
        else:
            self.dash_stop = threading.Event()
            self.dash_thread = threading.Thread(
                target=_dashboard_worker,
                kwargs=dict(
                    client=self.client,
                    stats=self.submit_stats,
                    stop=self.dash_stop,
                    refresh_rate=self.dash_refresh_rate,
                ),
                daemon=True,
            )
            self.dash_thread.start()

    def dashboard_is_active(self):
        return hasattr(self, "dash_thread") and self.dash_thread.is_alive()

    def stop_loading_claims(self):
        """Stop loading claims from the current file."""
        self.load_stop.set()
        if self.load_thread and self.load_thread.is_alive():
            self.load_thread.join(timeout=1)
        print("✔ stopped loading claims")

    def stop_generating_claims(self):
        """Stop generating random claims."""
        self.generate_stop.set()
        if self.generate_thread and self.generate_thread.is_alive():
            self.generate_thread.join(timeout=1)
        print("✔ stopped generating claims")

    # ---------- shutdown ---------- #

    def shutdown(self):
        self.submit_stop.set()
        self.stop_loading_claims()
        self.stop_generating_claims()
        if self.submit_thread and self.submit_thread.is_alive():
            self.submit_thread.join(timeout=1)
        self.client.channel.close()
        print("👋  goodbye")

    # ---------- misc ---------- #

    @staticmethod
    def _banner():
        _clear()
        print(
            "BraceHealth Billing CLI\n"
            "────────────────────────\n"
            f"Server: {SERVER_ADDR}\n"
        )


# ─────────────────────────── json→claim conversion ─────────────────── #


def _json_to_claim(j: Dict) -> pc_pb2.PayerClaim:
    """Very small shim that re-uses helper from submit_claims.py (copy-pasted)."""
    from generated import payer_claim_pb2, common_pb2  # local import for brevity

    def _to_currency_value(units: int, currency: str, unit_charge_amount: float):
        if currency != "USD":
            raise ValueError("Only USD supported")
        total = units * unit_charge_amount
        return common_pb2.CurrencyValue(
            decimal_amount=int(round(total * 100)), whole_amount=int(total)
        )

    gender_map = {"m": payer_claim_pb2.Gender.M, "f": payer_claim_pb2.Gender.F}

    insurance = payer_claim_pb2.Insurance(
        payer_id=payer_claim_pb2.PayerId.Value(j["insurance"]["payer_id"].upper()),
        patient_member_id=j["insurance"]["patient_member_id"],
    )
    p = j["patient"]
    patient = payer_claim_pb2.Patient(
        first_name=p["first_name"],
        last_name=p["last_name"],
        gender=gender_map[p["gender"].lower()],
        dob=p["dob"],
    )
    org = payer_claim_pb2.Organization(name=j["organization"]["name"])
    provider = payer_claim_pb2.RenderingProvider(
        first_name=j["rendering_provider"]["first_name"],
        last_name=j["rendering_provider"]["last_name"],
        npi=j["rendering_provider"]["npi"],
    )
    service_lines = []
    for sl in j["service_lines"]:
        service_lines.append(
            payer_claim_pb2.ServiceLine(
                service_line_id=sl["service_line_id"],
                procedure_code=sl["procedure_code"],
                details=sl["details"],
                charge=_to_currency_value(
                    sl["units"], sl["unit_charge_currency"], sl["unit_charge_amount"]
                ),
            )
        )
    return payer_claim_pb2.PayerClaim(
        claim_id=j["claim_id"],
        place_of_service_code=j["place_of_service_code"],
        insurance=insurance,
        patient=patient,
        organization=org,
        rendering_provider=provider,
        service_lines=service_lines,
    )


# ─────────────────────────── entrypoint ────────────────────────────── #


def main():
    # graceful Ctrl-C across threads
    signal.signal(signal.SIGINT, lambda *_: None)
    CLI().start()


if __name__ == "__main__":
    main()

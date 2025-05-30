#!/usr/bin/env python3
"""
Periodically poll the BillingService for:
  1️⃣ Accounts-receivable aging by payer
  2️⃣ Patient-responsibility running totals

Usage
-----
$ python poll_billing_service.py                       # 5-second cadence
$ python poll_billing_service.py --interval 2          # 2-second cadence
"""
from __future__ import annotations

import argparse
import os
import time
from typing import Dict, List, Tuple
from datetime import datetime
import grpc

from generated import billing_pb2, billing_pb2_grpc


# ──────────────────────────── Constants ───────────────────────────── #

# Four aging buckets expressed in *seconds ago* relative to "now"
# (start is the older edge, end the newer edge; end==0 ⇒ present moment)
AGING_BUCKETS: List[Tuple[str, int | None, int | None]] = [
    ("0-1 min", 60, 0),
    ("1-2 min", 120, 60),
    ("2-3 min", 180, 120),
    ("3+ min", None, 180),  # start unspecified ⇒ "back forever"
]

CURRENCY_FMT = "${:,.0f}"  # show whole-dollar amounts with commas


# ──────────────────────────── Formatting ──────────────────────────── #


def _pad(value: str, width: int) -> str:
    return value.ljust(width)


def _format_table(headers: List[str], rows: List[List[str]]) -> str:
    col_widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            col_widths[i] = max(col_widths[i], len(cell))

    def fmt_row(cells: List[str]) -> str:
        return (
            "| "
            + " | ".join(_pad(c, col_widths[i]) for i, c in enumerate(cells))
            + " |"
        )

    rule = "|-" + "-|-".join("-" * w for w in col_widths) + "-|"
    lines = [fmt_row(headers), rule]
    lines += [fmt_row(r) for r in rows]
    return "\n".join(lines)


# ──────────────────────────── Builders ────────────────────────────── #


def _build_aging_request() -> billing_pb2.GetAccountsReceivableRequest:
    req = billing_pb2.GetAccountsReceivableRequest()
    for _, start, end in AGING_BUCKETS:
        bucket = req.bucket.add()
        if start is not None:
            bucket.start_seconds_ago = start
        if end is not None:
            bucket.end_seconds_ago = end
    return req


def _bucket_key(start: int, end: int) -> Tuple[int, int]:
    """Helper for hashing proto bucket edges."""
    return start, end


# ──────────────────────────── Printers ────────────────────────────── #


def render_aging_table(resp: billing_pb2.GetAccountsReceivableResponse) -> str:
    # Map (start,end) → label for quick lookup
    bucket_lookup = {
        (start or 0, end or -1): label  # None ➜ 0/-1 just for keying
        for label, start, end in AGING_BUCKETS
    }
    header = ["Payer"] + [b[0] for b in AGING_BUCKETS] + ["TOTAL"]
    rows: List[List[str]] = []

    for row in resp.row:
        amounts_by_label: Dict[str, float] = {b[0]: 0.0 for b in AGING_BUCKETS}
        total = 0.0
        for bv in row.bucket_value:
            key = _bucket_key(
                bv.bucket.start_seconds_ago or 0,
                bv.bucket.end_seconds_ago or -1,
            )
            label = bucket_lookup.get(key)
            if label:
                amounts_by_label[label] += bv.amount
                total += bv.amount

        rows.append(
            [row.payer_id]
            + [CURRENCY_FMT.format(amounts_by_label[b[0]]) for b in AGING_BUCKETS]
            + [CURRENCY_FMT.format(total)]
        )

    return _format_table(header, rows)


def render_patient_table(resp: billing_pb2.GetPatientAccountsReceivableResponse) -> str:
    header = ["Patient member ID", "Σ Copay", "Σ Coinsurance", "Σ Deductible"]
    rows = []
    for r in resp.row:
        rows.append(
            [
                r.patient.first_name + " " + r.patient.last_name,
                CURRENCY_FMT.format(r.outstanding_copay),
                CURRENCY_FMT.format(r.outstanding_coinsurance),
                CURRENCY_FMT.format(r.outstanding_deductible),
            ]
        )
    return _format_table(header, rows)


# ──────────────────────────── Main loop ───────────────────────────── #


def run(interval: float) -> None:
    channel = grpc.insecure_channel("localhost:9090")
    stub = billing_pb2_grpc.BillingServiceStub(channel)

    aging_req = _build_aging_request()

    count = 0
    while True:
        try:
            ar_resp = stub.getAccountsReceivable(aging_req, timeout=3)
            pt_resp = stub.getPatientAccountsReceivable(
                billing_pb2.GetPatientAccountsReceivableRequest(), timeout=3
            )
        except grpc.RpcError as err:
            print("█ gRPC error:", err.details() or err)
            time.sleep(interval)
            continue

        os.system("clear" if os.name == "posix" else "cls")
        print(
            f"== Brace Health - Real-time (updated every {interval:.1f}s) Billing Monitor =="
        )
        print(f"Last udpated at {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

        print("1️⃣  AR aging by payer")
        print(render_aging_table(ar_resp))
        print("\n2️⃣  Patient-responsibility summary")
        print(render_patient_table(pt_resp))

        time.sleep(interval)
        count += 1


# ──────────────────────────── Entrypoint ──────────────────────────── #


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Poll BillingService for AR & patient stats"
    )
    parser.add_argument(
        "-i",
        "--interval",
        type=float,
        default=5.0,
        help="Polling interval in seconds (default: 5)",
    )
    args = parser.parse_args()

    if args.interval <= 0:
        parser.error("--interval must be > 0")

    run(args.interval)


if __name__ == "__main__":
    main()

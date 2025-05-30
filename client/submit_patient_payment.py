#!/usr/bin/env python3
"""
Record a patient payment against an open claim.

Examples
--------
# pay $40 toward claim CLM1234567 on the default endpoint
$ python submit_patient_payment.py CLM1234567 40

# same but custom BillingService host:port
$ python submit_patient_payment.py CLM1234567 40 --host 127.0.0.1:5000
"""
from __future__ import annotations

import argparse
import sys

import grpc

from generated import billing_pb2, billing_pb2_grpc


# ────────────────────────── constants ────────────────────────── #

DEFAULT_HOST = "localhost:9090"  # BillingService gRPC endpoint
TIMEOUT = 5  # seconds


# ────────────────────────── helpers ────────────────────────── #


def format_result(res: billing_pb2.SubmitPatientPaymentResult) -> str:
    """Turn enum → friendly string."""
    mapping = {
        billing_pb2.SUBMIT_PATIENT_PAYMENT_RESULT_SUCCESS: "SUCCESS",
        billing_pb2.SUBMIT_PATIENT_PAYMENT_RESULT_FAILURE: "FAILURE",
        billing_pb2.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE: "NO BALANCE",
    }
    return mapping.get(res, f"UNKNOWN({res})")


# ────────────────────────── main ─────────────────────────── #


def main() -> None:
    p = argparse.ArgumentParser(description="Submit a patient payment")
    p.add_argument("claim_id", help="target claim_id (e.g. CLM1234567)")
    p.add_argument("amount", type=float, help="payment amount (USD)")
    p.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help=f"BillingService host:port (default {DEFAULT_HOST})",
    )
    args = p.parse_args()

    if args.amount <= 0:
        sys.exit("amount must be positive")

    channel = grpc.insecure_channel(args.host)
    stub = billing_pb2_grpc.BillingServiceStub(channel)

    req = billing_pb2.SubmitPatientPaymentRequest(
        claim_id=args.claim_id,
        amount=args.amount,
    )

    try:
        resp = stub.submitPatientPayment(req, timeout=TIMEOUT)
    except grpc.RpcError as err:
        sys.exit(f"gRPC error: {err.details() or err}")

    print(f"Payment result for {args.claim_id}:", format_result(resp.result))


if __name__ == "__main__":
    main()

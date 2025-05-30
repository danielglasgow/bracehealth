#!/usr/bin/env python3
"""
Submit a stream of PayerClaim JSON-lines to the ClearingHouseService.

Examples
--------
# 1 claim / second (default) to localhost:9091
$ python submit_to_clearinghouse.py claims.jsonl

# 2 claims / sec, custom host
$ python submit_to_clearinghouse.py claims.jsonl --rate 0.5 --host 127.0.0.1:6000
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

import grpc

from generated import billing_pb2, billing_pb2_grpc

# ────────────────────────── constants ────────────────────────── #

RETRY_LIMIT = 10  # max submit retries per claim
DEFAULT_HOST = "localhost:9091"  # ClearingHouseService gRPC endpoint


# ────────────────────────── helpers ─────────────────────────── #


def _gender_enum(g: str) -> billing_pb2.Gender:
    try:
        return {"m": billing_pb2.Gender.M, "f": billing_pb2.Gender.F}[g.lower()]
    except KeyError:
        raise ValueError(f"Invalid gender {g!r}")


def _assert_date(date_str: str) -> str:
    datetime.strptime(date_str, "%Y-%m-%d")
    return date_str


def json_to_payer_claim(src: dict) -> billing_pb2.PayerClaim:
    """Convert a schema-conforming dict to a PayerClaim protobuf message."""
    insurance = billing_pb2.Insurance(
        payer_id=billing_pb2.PayerId.Value(src["insurance"]["payer_id"].upper()),
        patient_member_id=src["insurance"]["patient_member_id"],
    )

    patient_addr = src["patient"].get("address", {})
    patient = billing_pb2.Patient(
        first_name=src["patient"]["first_name"],
        last_name=src["patient"]["last_name"],
        email=src["patient"].get("email", ""),
        gender=_gender_enum(src["patient"]["gender"]),
        dob=_assert_date(src["patient"]["dob"]),
        address=billing_pb2.Address(**patient_addr),
    )

    org_addr = src["organization"].get("address", {})
    org_contact = src["organization"].get("contact", {})
    organization = billing_pb2.Organization(
        name=src["organization"]["name"],
        billing_npi=src["organization"].get("billing_npi", ""),
        ein=src["organization"].get("ein", ""),
        contact=billing_pb2.Contact(**org_contact),
        address=billing_pb2.Address(**org_addr),
    )

    rendering_provider = billing_pb2.RenderingProvider(
        first_name=src["rendering_provider"]["first_name"],
        last_name=src["rendering_provider"]["last_name"],
        npi=src["rendering_provider"]["npi"],
    )

    service_lines = [
        billing_pb2.ServiceLine(
            service_line_id=sl["service_line_id"],
            procedure_code=sl["procedure_code"],
            modifiers=sl.get("modifiers", []),
            units=sl["units"],
            details=sl["details"],
            unit_charge_currency=sl["unit_charge_currency"],
            unit_charge_amount=sl["unit_charge_amount"],
            do_not_bill=sl.get("do_not_bill", False),
        )
        for sl in src["service_lines"]
    ]

    return billing_pb2.PayerClaim(
        claim_id=src["claim_id"],
        place_of_service_code=src["place_of_service_code"],
        insurance=insurance,
        patient=patient,
        organization=organization,
        rendering_provider=rendering_provider,
        service_lines=service_lines,
    )


def submit_with_retry(
    stub: billing_pb2_grpc.ClearingHouseServiceStub,
    claim: billing_pb2.PayerClaim,
) -> bool:
    """Attempt to submit a claim, retrying transient failures."""
    for attempt in range(1, RETRY_LIMIT + 1):
        try:
            ok = stub.submitClaim(
                billing_pb2.SubmitClaimRequest(claim=claim),
                timeout=5,
            ).success
            if ok:
                print("✅", claim.claim_id)
                return True
            print("❌", claim.claim_id, f"(attempt {attempt})")
        except grpc.RpcError as err:
            print(f"⚠️  gRPC error on {claim.claim_id}: {err} (attempt {attempt})")
        time.sleep(1)

    print("🚫 giving up:", claim.claim_id)
    return False


# ────────────────────────── CLI / main ─────────────────────────── #


def main() -> None:
    p = argparse.ArgumentParser(description="Stream claims to ClearingHouseService")
    p.add_argument(
        "file", type=Path, help="JSON-lines file (one PayerClaim object per line)"
    )
    p.add_argument(
        "-r",
        "--rate",
        type=float,
        default=1.0,
        help="seconds between submissions (default 1.0)",
    )
    p.add_argument(
        "--host", default=DEFAULT_HOST, help=f"gRPC host:port (default {DEFAULT_HOST})"
    )
    args = p.parse_args()

    if args.rate <= 0:
        sys.exit("rate must be > 0")

    if not args.file.exists():
        sys.exit(f"File not found: {args.file}")

    channel = grpc.insecure_channel(args.host)
    stub = billing_pb2_grpc.ClearingHouseServiceStub(channel)

    with args.file.open() as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                claim = json_to_payer_claim(json.loads(line))
            except Exception as exc:
                print(f"[line {lineno}] skipped: {exc}")
                continue

            submit_with_retry(stub, claim)
            time.sleep(args.rate)

    print("✔ all claims processed")


if __name__ == "__main__":
    main()

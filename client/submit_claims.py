#!/usr/bin/env python3
"""
Submit a stream of PayerClaim JSON lines to the BillingService.

Examples
--------
# default 1 claim / sec
$ python submit_claims.py claims.jsonl

# 0.5 claims / sec via CLI flag
$ python submit_claims.py claims.jsonl --rate 0.5

# 2 claims / sec via env var (overridden if --rate is also given)
$ CLAIM_RATE=0.5 python submit_claims.py claims.jsonl
"""
import argparse
import json
import sys
import time
from pathlib import Path

import grpc
import datetime

from generated import billing_service_pb2, billing_service_pb2_grpc
from generated import payer_claim_pb2, common_pb2

# Maximum number of retry attempts for claim submission
RETRY_LIMIT = 10

# ───────────────────────── helpers ────────────────────────── #


def _to_currency_value(
    units: int, currency: str, unit_charge_amount: float
) -> common_pb2.CurrencyValue:
    if currency != "USD":
        raise ValueError(f"Only USD is supported")
    total = units * unit_charge_amount
    return common_pb2.CurrencyValue(
        decimal_amount=int(total * 100),
        whole_amount=int(total),
    )


def _get_gender_enum(gender: str) -> payer_claim_pb2.Gender:
    mapping = {"m": payer_claim_pb2.Gender.M, "f": payer_claim_pb2.Gender.F}
    try:
        return mapping[gender.lower()]
    except KeyError:
        raise ValueError(f"Invalid gender: {gender}")


def _validate_date(date: str) -> str:
    try:
        datetime.datetime.strptime(date, "%Y-%m-%d")
        return date
    except ValueError:
        raise ValueError(f"Invalid date: {date}")


def json_to_payer_claim(j: dict) -> payer_claim_pb2.PayerClaim:
    insurance = payer_claim_pb2.Insurance(
        payer_id=payer_claim_pb2.PayerId.Value(j["insurance"]["payer_id"].upper()),
        patient_member_id=j["insurance"]["patient_member_id"],
    )

    patient_addr = j["patient"].get("address", {})
    patient = payer_claim_pb2.Patient(
        first_name=j["patient"]["first_name"],
        last_name=j["patient"]["last_name"],
        email=j["patient"].get("email", ""),
        gender=_get_gender_enum(j["patient"]["gender"]),
        dob=_validate_date(j["patient"]["dob"]),
        address=payer_claim_pb2.Address(**patient_addr),
    )

    org_addr = j["organization"].get("address", {})
    org_contact = j["organization"].get("contact", {})
    organization = payer_claim_pb2.Organization(
        name=j["organization"]["name"],
        billing_npi=j["organization"].get("billing_npi", ""),
        ein=j["organization"].get("ein", ""),
        contact=payer_claim_pb2.Contact(**org_contact),
        address=payer_claim_pb2.Address(**org_addr),
    )

    rendering_provider = payer_claim_pb2.RenderingProvider(
        first_name=j["rendering_provider"]["first_name"],
        last_name=j["rendering_provider"]["last_name"],
        npi=j["rendering_provider"]["npi"],
    )

    service_lines = [
        payer_claim_pb2.ServiceLine(
            service_line_id=sl["service_line_id"],
            procedure_code=sl["procedure_code"],
            modifiers=sl.get("modifiers", []),
            details=sl["details"],
            charge=_to_currency_value(
                sl["units"], sl["unit_charge_currency"], sl["unit_charge_amount"]
            ),
            do_not_bill=sl.get("do_not_bill", False),
        )
        for sl in j["service_lines"]
    ]

    return payer_claim_pb2.PayerClaim(
        claim_id=j["claim_id"],
        place_of_service_code=j["place_of_service_code"],
        insurance=insurance,
        patient=patient,
        organization=organization,
        rendering_provider=rendering_provider,
        service_lines=service_lines,
    )


def submit_claim(
    stub: billing_service_pb2_grpc.BillingServiceStub,
    claim_msg: payer_claim_pb2.PayerClaim,
) -> None:
    retry_count = 0
    while retry_count < RETRY_LIMIT:
        try:
            result = stub.submitClaim(
                billing_service_pb2.SubmitClaimRequest(claim=claim_msg),
                timeout=5,
            ).result
            if (
                result
                == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS
            ):
                return result
            elif (
                result
                == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED
            ):
                return result
            else:
                print("❌", "claim_id=", claim_msg.claim_id, "failed")
                retry_count += 1
                if retry_count < RETRY_LIMIT:
                    print(
                        f"⚠️  Retry {retry_count}/{RETRY_LIMIT} for claim_id={claim_msg.claim_id}"
                    )
                    time.sleep(1)
                else:
                    return result
        except grpc.RpcError as err:
            retry_count += 1
            print(f"❌ gRPC error: {err}")
            if retry_count < RETRY_LIMIT:
                print(
                    f"⚠️  Retry {retry_count}/{RETRY_LIMIT} for claim_id={claim_msg.claim_id}: {err}"
                )
                time.sleep(1)
            else:
                return billing_service_pb2.SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE


# ───────────────────────── main ────────────────────────── #


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Stream claims from a JSON-lines file to the BillingService"
    )
    parser.add_argument(
        "file",
        type=Path,
        help="File containing one JSON object per line (PayerClaim schema)",
    )
    parser.add_argument(
        "-r",
        "--rate",
        type=float,
        default=None,
        metavar="SECONDS",
        help="Seconds between claim submissions (default 1.0)",
    )
    args = parser.parse_args()

    rate = args.rate if args.rate is not None else 1.0
    if rate <= 0:
        sys.exit("✖ rate must be > 0 seconds")

    if not args.file.exists():
        sys.exit(f"✖ File not found: {args.file}")

    channel = grpc.insecure_channel("localhost:9090")
    stub = billing_service_pb2_grpc.BillingServiceStub(channel)

    with args.file.open() as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                claim_msg = json_to_payer_claim(json.loads(line))
            except Exception as exc:
                print(f"[line {lineno}] ⚠️  skipped: {exc}")
                continue

            result = submit_claim(stub, claim_msg)
            if (
                result
                == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE
            ):
                print(
                    f"❌ Failed after {RETRY_LIMIT} attempts for claim_id={claim_msg.claim_id}"
                )
            elif (
                result
                == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED
            ):
                print(f"⚠️  claim_id={claim_msg.claim_id} already submitted. Skipping.")
            elif (
                result
                == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS
            ):
                print(f"✅ claim_id={claim_msg.claim_id} submitted")
                time.sleep(rate)
            else:
                print(f"❌ Unknown result: {result}")

    print("✔ done")


if __name__ == "__main__":
    main()

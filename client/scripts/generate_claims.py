#!/usr/bin/env python3
"""
Generate synthetic PayerClaim records and save them as JSON-lines.

Examples
--------
# 10 claims into claims.jsonl
$ python faker_claim.py 10 claims.jsonl
"""
import argparse
import json
import random
import string
from datetime import date, timedelta
from pathlib import Path
from typing import Dict, List, Tuple

# ───── pools & helpers (unchanged, trimmed for brevity) ────────── #
PAYERS = ["medicare", "united_health_group", "anthem"]
FIRST_NAMES_F = ["Jane", "Emily", "Olivia", "Sophia", "Emma"]
FIRST_NAMES_M = ["John", "Michael", "James", "David", "Robert"]
LAST_NAMES = ["Doe", "Smith", "Johnson", "Brown", "Garcia"]
CITIES: List[Tuple[str, str, str]] = [
    ("Boston", "MA", "02115"),
    ("Austin", "TX", "73301"),
    ("Seattle", "WA", "98101"),
    ("Denver", "CO", "80202"),
]
CLINIC_NAMES = ["Brace Health Clinic", "Sunrise Medical", "Wellness Partners"]
PROCEDURE_CODES = [
    ("99213", "Office/outpatient visit", 55, 95),
    ("87070", "Bacterial culture", 40, 85),
    ("81002", "Urinalysis", 10, 25),
    ("80053", "CMP, comprehensive", 25, 60),
]
PLACE_OF_SERVICE_CODES = [11, 19, 22]
CURRENCY = "USD"


def _rand_date(start_year=1950, end_year=2005) -> str:
    start = date(start_year, 1, 1)
    delta = date(end_year, 12, 31) - start
    return (start + timedelta(days=random.randint(0, delta.days))).isoformat()


def _rand_digits(n: int) -> str:
    return "".join(random.choices(string.digits, k=n))


def _rand_email(first: str, last: str) -> str:
    return f"{first.lower()}.{last.lower()}@{random.choice(['example.com','mail.com','health.org'])}"


def _random_service_line(idx: int) -> Dict:
    code, details, lo, hi = random.choice(PROCEDURE_CODES)
    return {
        "service_line_id": f"SL{idx:03d}",
        "procedure_code": code,
        "modifiers": random.sample(["25", "59", "AA", "TC"], k=random.choice([0, 1])),
        "units": random.randint(1, 3),
        "details": details,
        "unit_charge_currency": CURRENCY,
        "unit_charge_amount": round(random.uniform(lo, hi), 2),
        "do_not_bill": random.random() < 0.05,
    }


# ───────────── main generator (unchanged) ───────────── #
def generate_random_claim() -> Dict:
    gender = random.choice(["m", "f"])
    first_name = random.choice(FIRST_NAMES_F if gender == "f" else FIRST_NAMES_M)
    last_name = random.choice(LAST_NAMES)
    city, state, zip_code = random.choice(CITIES)

    patient = {
        "first_name": first_name,
        "last_name": last_name,
        "email": _rand_email(first_name, last_name),
        "gender": gender,
        "dob": _rand_date(),
        "address": {
            "street": f"{random.randint(100,999)} Main St",
            "city": city,
            "state": state,
            "zip": zip_code,
            "country": "USA",
        },
    }
    organization = {
        "name": random.choice(CLINIC_NAMES),
        "billing_npi": _rand_digits(10),
        "ein": f"{_rand_digits(2)}-{_rand_digits(7)}",
        "contact": {
            "first_name": random.choice(FIRST_NAMES_F + FIRST_NAMES_M),
            "last_name": random.choice(LAST_NAMES),
            "phone_number": f"555-{_rand_digits(3)}-{_rand_digits(4)}",
        },
        "address": {
            "street": f"{random.randint(400,999)} Health Ave",
            "city": city,
            "state": state,
            "zip": zip_code,
            "country": "USA",
        },
    }
    provider = {
        "first_name": random.choice(FIRST_NAMES_F + FIRST_NAMES_M),
        "last_name": random.choice(LAST_NAMES),
        "npi": _rand_digits(10),
    }
    service_lines = [_random_service_line(i + 1) for i in range(random.randint(1, 3))]

    return {
        "claim_id": "CLM" + _rand_digits(7),
        "place_of_service_code": random.choice(PLACE_OF_SERVICE_CODES),
        "insurance": {
            "payer_id": random.choice(PAYERS),
            "patient_member_id": "PM" + _rand_digits(9),
        },
        "patient": patient,
        "organization": organization,
        "rendering_provider": provider,
        "service_lines": service_lines,
    }


# ───────────── CLI entrypoint ───────────── #
def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate N random PayerClaim records to a JSON-lines file"
    )
    parser.add_argument("num", type=int, help="number of claims to generate (N)")
    parser.add_argument(
        "outfile", type=Path, help="output file path (will be overwritten)"
    )
    args = parser.parse_args()

    if args.num <= 0:
        parser.error("num must be > 0")

    with args.outfile.open("w", encoding="utf-8") as fh:
        for _ in range(args.num):
            fh.write(json.dumps(generate_random_claim()) + "\n")

    print(f"✓ Wrote {args.num} claims to {args.outfile}")


if __name__ == "__main__":
    main()

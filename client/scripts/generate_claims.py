#!/usr/bin/env python3
"""
Generate synthetic PayerClaim records and save them as JSON-lines.

Examples
--------
# 10 claims into claims.txt
$ python faker_claim.py 10 claims.txt
"""
import sys
import os

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))


import argparse
import json
from pathlib import Path
from claim_util import generate_random_claim


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

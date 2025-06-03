import os
from datetime import datetime

from generated import billing_service_pb2, common_pb2


# Four aging buckets expressed in *seconds ago* relative to "now"
# (start is the older edge, end the newer edge; end==0 ⇒ present moment)
AGING_BUCKETS: list[tuple[str, int | None, int | None]] = [
    ("0-1 min", 60, 0),
    ("1-2 min", 120, 60),
    ("2-3 min", 180, 120),
    ("3+ min", None, 180),  # start unspecified ⇒ "back forever"
]

CURRENCY_FMT = "${:,.2f}"  # show dollar amounts with cents and commas


def _pad(value: str, width: int) -> str:
    return value.ljust(width)


def _format_table(headers: list[str], rows: list[list[str]]) -> str:
    col_widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            col_widths[i] = max(col_widths[i], len(cell))

    def fmt_row(cells: list[str]) -> str:
        return (
            "| "
            + " | ".join(_pad(c, col_widths[i]) for i, c in enumerate(cells))
            + " |"
        )

    rule = "|-" + "-|-".join("-" * w for w in col_widths) + "-|"
    lines = [fmt_row(headers), rule]
    lines += [fmt_row(r) for r in rows]
    return "\n".join(lines)


def _bucket_key(start: int, end: int) -> tuple[int, int]:
    """Helper for hashing proto bucket edges."""
    return start, end


def _render_aging_table(
    resp: billing_service_pb2.GetPayerAccountsReceivableResponse,
) -> str:
    # Map (start,end) → label for quick lookup
    bucket_lookup = {
        (start or 0, end or -1): label  # None ➜ 0/-1 just for keying
        for label, start, end in AGING_BUCKETS
    }
    header = ["Payer"] + [b[0] for b in AGING_BUCKETS] + ["TOTAL"]
    rows: list[list[str]] = []

    for row in resp.row:
        amounts_by_label: dict[str, float] = {b[0]: 0.0 for b in AGING_BUCKETS}
        total = 0.0
        for bv in row.bucket_value:
            key = _bucket_key(
                bv.bucket.start_seconds_ago or 0,
                bv.bucket.end_seconds_ago or -1,
            )
            label = bucket_lookup.get(key)
            if label:
                amount = bv.amount.whole_amount + bv.amount.decimal_amount / 100
                amounts_by_label[label] += amount
                total += amount

        rows.append(
            [row.payer_id]
            + [CURRENCY_FMT.format(amounts_by_label[b[0]]) for b in AGING_BUCKETS]
            + [CURRENCY_FMT.format(total)]
        )

    return _format_table(header, rows)


def _currency_value_to_float(cv: common_pb2.CurrencyValue) -> float:
    return cv.whole_amount + cv.decimal_amount / 100


# There is a bug here or in the API (I think the API), where we're not properly aggregating by patient name
# This appears to have a row per claim id
def _render_patient_table(
    resp: billing_service_pb2.GetPatientAccountsReceivableResponse,
) -> str:
    header = [
        "Patient member ID",
        "Claim ID",
        "Σ Copay",
        "Σ Coinsurance",
        "Σ Deductible",
    ]
    rows = []
    for row in resp.row:
        balance = row.balance
        outstanding_copay = _currency_value_to_float(balance.outstanding_copay)
        outstanding_coinsurance = _currency_value_to_float(
            balance.outstanding_coinsurance
        )
        outstanding_deductible = _currency_value_to_float(
            balance.outstanding_deductible
        )
        rows.append(
            [
                row.patient.first_name + " " + row.patient.last_name,
                ", ".join(row.claim_id),  # Join multiple claim IDs with commas
                CURRENCY_FMT.format(outstanding_copay),
                CURRENCY_FMT.format(outstanding_coinsurance),
                CURRENCY_FMT.format(outstanding_deductible),
            ]
        )
    return _format_table(header, rows)


def render_dashboard(
    interval: float,
    last_updated_time: datetime,
    ar_resp: billing_service_pb2.GetPayerAccountsReceivableResponse,
    pt_resp: billing_service_pb2.GetPatientAccountsReceivableResponse,
) -> None:
    os.system("clear" if os.name == "posix" else "cls")
    print(
        f"== Brace Health - Real-time (updated every {interval:.1f}s) Billing Monitor =="
    )
    print(f"Last udpated at {last_updated_time.strftime('%Y-%m-%d %H:%M:%S')}\n")

    print("1️⃣  AR aging by payer")
    print(_render_aging_table(ar_resp))
    print("\n2️⃣  Patient-responsibility summary")
    print(_render_patient_table(pt_resp))

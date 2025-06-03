import os
from datetime import datetime
import grpc
from pydantic import BaseModel

from generated import billing_service_pb2, common_pb2


CURRENCY_FMT = "${:,.2f}"  # show dollar amounts with cents and commas

# start seconds ago, end seconds ago
SLOW_BUCKETS: list[tuple[str, int, int]] = [
    ("0-1 min", 60, 0),
    ("1-2 min", 120, 60),
    ("2-3 min", 180, 120),
    ("3+ min", 0, 180),
]

# start seconds ago, end seconds ago
FAST_BUCKETS: list[tuple[str, int, int]] = [
    ("0-10s", 10, 0),
    ("10-20s", 20, 10),
    ("20-30s", 30, 20),
    ("30+s", 0, 30),
]

# start seconds ago, end seconds ago
LIGHTNING_BUCKETS: list[tuple[str, int, int]] = [
    ("0-1s", 1, 0),
    ("1-2s", 2, 1),
    ("2-3s", 3, 2),
    ("3+s", 0, 3),
]


class DashboardState(BaseModel):
    last_updated_time: datetime
    last_patients_ar_response: billing_service_pb2.GetPatientAccountsReceivableResponse
    last_aging_ar_response: billing_service_pb2.GetPayerAccountsReceivableResponse

    # Allows for the grpc types
    model_config = {"arbitrary_types_allowed": True}


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
    lines.append(rule)
    return "\n".join(lines)


def _render_aging_table(
    resp: billing_service_pb2.GetPayerAccountsReceivableResponse,
    buckets: list[tuple[str, int, int]],
) -> str:
    # Map (start,end) → label for quick lookup
    bucket_lookup = {(start, end): label for label, start, end in buckets}
    header = ["Payer"] + [b[0] for b in buckets] + ["TOTAL"]
    rows: list[list[str]] = []

    for row in resp.row:
        amounts_by_label: dict[str, float] = {b[0]: 0.0 for b in buckets}
        total = 0.0
        for bv in row.bucket_value:
            key = (bv.bucket.start_seconds_ago, bv.bucket.end_seconds_ago)
            label = bucket_lookup.get(key)
            if label:
                amount = bv.amount.whole_amount + bv.amount.decimal_amount / 100
                amounts_by_label[label] += amount
                total += amount

        rows.append(
            [row.payer_id]
            + [CURRENCY_FMT.format(amounts_by_label[b[0]]) for b in buckets]
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
        claim_ids = [id for id in row.claim_id]
        if len(claim_ids) > 3:
            claim_ids = claim_ids[:3] + ["..."]
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
                ", ".join(claim_ids),
                CURRENCY_FMT.format(outstanding_copay),
                CURRENCY_FMT.format(outstanding_coinsurance),
                CURRENCY_FMT.format(outstanding_deductible),
            ]
        )
    return _format_table(header, rows)


def _get_successful_claims(
    claim_responses: dict[str, billing_service_pb2.SubmitClaimResponse | grpc.RpcError],
) -> list[str]:
    return [
        id
        for id, resp in claim_responses.items()
        if isinstance(resp, billing_service_pb2.SubmitClaimResponse)
        and resp.result
        == billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS
    ]


def _get_failed_claims(
    claim_responses: dict[str, billing_service_pb2.SubmitClaimResponse | grpc.RpcError],
) -> list[str]:
    return [
        id
        for id, resp in claim_responses.items()
        if isinstance(resp, grpc.RpcError)
        or resp.result
        != billing_service_pb2.SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS
    ]


def render_dashboard(
    interval: float,
    state: DashboardState,
    buckets: list[tuple[str, int, int]],
    claim_responses: dict[str, billing_service_pb2.SubmitClaimResponse | grpc.RpcError],
) -> None:
    os.system("clear" if os.name == "posix" else "cls")
    print(
        f"== Brace Health - Real-time (updated every {interval:.1f}s) Billing Monitor =="
    )
    print(f"Last udpated at {state.last_updated_time.strftime('%Y-%m-%d %H:%M:%S')}")
    print(
        f"Successfully submitted claim count: {len(_get_successful_claims(claim_responses))}"
    )
    print(f"Failed claim count: {len(_get_failed_claims(claim_responses))}")

    print("\nAR aging by payer\n")
    print(_render_aging_table(state.last_aging_ar_response, buckets))
    print("\nPatient-responsibility summary\n")
    print(_render_patient_table(state.last_patients_ar_response))

from collections import defaultdict
import grpc
from typing import Callable
from src.generated import billing_service_pb2, payer_claim_pb2
from src.dashboard import (
    CURRENCY_FMT,
    format_table,
)

from src.billing_client import BillingClient
from src.common import currency_value_to_float, to_patient_id, clear_screen

CLAIM_STATUS_MAP = {
    0: "UNKNOWN",
    1: "SUBMITTED",
    2: "REMITTENCE_RECEIVED",
    3: "PAID",
}

PAY_CLAIM_RESULT_MAP = {
    0: "UNSPECIFIED",
    1: "ERROR",
    2: "NO_OUTSTANDING_BALANCE",
    3: "AMOUNT_EXCEEDS_OUTSTANDING_BALANCE",
    4: "FULLY_PAID",
    5: "PAYMENT_APPLIED_BALANCING_OUTSTANDING",
}


def view_claims_for_patient(client: BillingClient) -> dict[int, str]:
    patients: dict[str, payer_claim_pb2.Patient] = {}  # patient_id -> patient
    patient_id_to_rows: dict[
        str,
        list[billing_service_pb2.GetPatientClaimsResponse.PatientAccountsReceivableRow],
    ] = defaultdict(list)
    response = client.ar_by_patient()
    for row in response.row:
        patient_id = to_patient_id(row.patient)
        patients[patient_id] = row.patient
        patient_id_to_rows[patient_id].append(row)

    patient_id_to_balance: dict[str, float] = defaultdict(float)
    for patient_id, rows in patient_id_to_rows.items():
        for row in rows:
            total_balance = sum(
                [
                    currency_value_to_float(row.balance.outstanding_copay),
                    currency_value_to_float(row.balance.outstanding_coinsurance),
                    currency_value_to_float(row.balance.outstanding_deductible),
                ]
            )
            patient_id_to_balance[patient_id] += total_balance
    rows = []
    row_to_patient = {}
    for i, patient in enumerate(
        sorted(patients.values(), key=lambda x: (x.last_name, x.first_name))
    ):
        patient_id = to_patient_id(patient)
        row_to_patient[i + 1] = patient
        rows.append(
            {
                "patient_name": f"{patient.first_name} {patient.last_name}",
                "balance": patient_id_to_balance[patient_id],
                "claim_count": len(patient_id_to_rows[patient_id]),
            }
        )
    print(format_patients_table(rows))
    return row_to_patient


def format_claims_table(claims: billing_service_pb2.GetPatientClaimsResponse) -> str:
    header = ["#", "Claim ID", "Status", "Copay", "Coinsurance", "Deductible", "Total"]
    rows = []
    row_to_claim = {}
    for i, claim in enumerate(claims.row):
        copay = currency_value_to_float(claim.balance.outstanding_copay)
        coinsurance = currency_value_to_float(claim.balance.outstanding_coinsurance)
        deductible = currency_value_to_float(claim.balance.outstanding_deductible)
        total = copay + coinsurance + deductible
        row_to_claim[i + 1] = claim
        rows.append(
            [
                str(i + 1),
                claim.claim_id,
                CLAIM_STATUS_MAP.get(claim.status, "UNKNOWN"),
                CURRENCY_FMT.format(copay),
                CURRENCY_FMT.format(coinsurance),
                CURRENCY_FMT.format(deductible),
                CURRENCY_FMT.format(total),
            ]
        )
    print(format_table(header, rows))
    return row_to_claim


def format_patients_table(rows: list[dict]) -> str:
    header = ["#", "Patient Name", "Balance", "Claim Count"]
    row_values = []
    for i, row in enumerate(rows):
        row_values.append(
            [
                str(i + 1),
                row["patient_name"],
                CURRENCY_FMT.format(row["balance"]),
                str(row["claim_count"]),
            ]
        )
    return format_table(header, row_values)


def pay_claim(client: BillingClient, await_main_menu: Callable[[str], None]):
    print("Patients")
    row_to_patient = view_claims_for_patient(client)
    if len(row_to_patient) == 0:
        return await_main_menu("No patients found")
    choice = input("Select a patient: ").strip()
    choice_index = _parse_int_or_default(choice, 0)
    patient = row_to_patient[choice_index]
    if patient is None:
        patient = row_to_patient[0]
    patient_id = to_patient_id(patient)
    claims = client.patient_claims(patient_id)
    if (
        claims.error
        != billing_service_pb2.GetPatientClaimsResponse.GET_PATIENT_CLAIM_ERROR_UNSPECIFIED
    ):
        return await_main_menu("Error: " + claims.error)
    claims_with_outstanding_balance = [
        claim for claim in claims.row if _check_has_outstanding_balance(claim)
    ]
    if len(claims_with_outstanding_balance) == 0:
        return await_main_menu("No claims with outstanding balances")
    clear_screen()
    print("\nClaims for", f"{patient.first_name} {patient.last_name}")
    row_to_claim = format_claims_table(claims)
    if len(row_to_claim) == 0:
        return await_main_menu("Unexpected error: no claims found")
    choice = None
    while True:
        choice = input("Select a claim to pay (x to return to main menu): ").strip()
        if choice == "x":
            return
        choice_index = _parse_int_or_default(choice, -1)
        if choice_index in row_to_claim:
            break
        print("Invalid selection")
    claim = row_to_claim[choice_index]
    full_balance = sum(
        [
            currency_value_to_float(claim.balance.outstanding_copay),
            currency_value_to_float(claim.balance.outstanding_coinsurance),
            currency_value_to_float(claim.balance.outstanding_deductible),
        ]
    )
    amount = None
    while amount is None:
        amount = _get_amount_input(full_balance)
    if amount == "x":
        return
    print(f"Paying {CURRENCY_FMT.format(amount)} for claim {choice}")
    response = client.pay_claim(claim.claim_id, amount)
    if isinstance(response, grpc.RpcError):
        print("Error submitting claim")
    else:
        print(f"Payment result: {PAY_CLAIM_RESULT_MAP[response.result]}")
        format_claims_table(client.patient_claims(patient_id))
    return await_main_menu(None)


def _get_amount_input(full_balance: float) -> float | str:
    print("Enter amount to pay (x to return to main menu): ")
    amount = input().strip()
    if amount == "x":
        return "x"
    if not amount:
        print("Pay full balance? (y/n)")
        if input().strip().lower() == "y":
            return full_balance
        else:
            return None
    try:
        amount = float(amount)
        if amount > full_balance:
            print("Amount is greater than full balance. Ignoring.")
            return None
        return amount
    except ValueError:
        print("Invalid amount")
        return None


def _check_has_outstanding_balance(
    claim: billing_service_pb2.GetPatientClaimsResponse.PatientClaimRow,
) -> bool:
    # Only claims with REMITTENCE_RECEIVED status have balance information (otherwise it's fully paid, or not yet received)
    if claim.status != billing_service_pb2.GetPatientClaimsResponse.REMITTENCE_RECEIVED:
        return False

    # Check if there's any outstanding balance
    return (
        currency_value_to_float(claim.balance.outstanding_copay) > 0
        or currency_value_to_float(claim.balance.outstanding_coinsurance) > 0
        or currency_value_to_float(claim.balance.outstanding_deductible) > 0
    )


def _parse_int_or_default(s: str, default: int) -> int:
    try:
        return int(s)
    except ValueError:
        return default

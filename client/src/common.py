from src.generated import common_pb2, payer_claim_pb2
import os


def currency_value_to_float(cv: common_pb2.CurrencyValue) -> float:
    return cv.whole_amount + cv.decimal_amount / 100


def to_patient_id(patient: payer_claim_pb2.Patient) -> str:
    return f"{patient.first_name.lower()}_{patient.last_name.lower()}_{patient.dob.lower()}"


def clear_screen():
    os.system("clear" if os.name == "posix" else "cls")

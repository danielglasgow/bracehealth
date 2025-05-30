import pytest
from generated import billing_pb2
from submit_claims import json_to_payer_claim


def _valid_json() -> dict:
    return {
        "claim_id": "CLM1234567",
        "place_of_service_code": 11,
        "insurance": {"payer_id": "medicare", "patient_member_id": "PM123456789"},
        "patient": {
            "first_name": "Jane",
            "last_name": "Doe",
            "email": "jane.doe@example.com",
            "gender": "f",
            "dob": "1980-05-15",
            "address": {
                "street": "123 Main St",
                "city": "Boston",
                "state": "MA",
                "zip": "02115",
                "country": "USA",
            },
        },
        "organization": {
            "name": "Brace Health Clinic",
            "billing_npi": "1234567890",
            "ein": "12-3456789",
            "contact": {
                "first_name": "Alice",
                "last_name": "Smith",
                "phone_number": "555-123-4567",
            },
            "address": {
                "street": "456 Health Ave",
                "city": "Boston",
                "state": "MA",
                "zip": "02116",
                "country": "USA",
            },
        },
        "rendering_provider": {
            "first_name": "John",
            "last_name": "Smith",
            "npi": "9876543210",
        },
        "service_lines": [
            {
                "service_line_id": "SL001",
                "procedure_code": "99213",
                "modifiers": ["25"],
                "units": 1,
                "details": "Office/outpatient visit",
                "unit_charge_currency": "USD",
                "unit_charge_amount": 75.0,
                "do_not_bill": False,
            },
            {
                "service_line_id": "SL002",
                "procedure_code": "87070",
                "modifiers": [],
                "units": 1,
                "details": "Bacterial culture, any source except urine/blood/stool",
                "unit_charge_currency": "USD",
                "unit_charge_amount": 50.0,
                "do_not_bill": False,
            },
        ],
    }


def test_json_to_payer_claim_success():
    result = json_to_payer_claim(_valid_json())

    assert isinstance(result, billing_pb2.PayerClaim)

    # Not sure if the rest of these are worthwhile, they are kind of just "change detector tests"
    # Would be nice to just have a test per field based on if it's required or not and testing any validation logic
    assert result.claim_id == "CLM1234567"
    assert result.place_of_service_code == 11
    assert result.insurance.payer_id == billing_pb2.PayerId.MEDICARE
    assert result.patient.first_name == "Jane"
    assert result.patient.last_name == "Doe"
    assert result.patient.gender == billing_pb2.Gender.F
    assert result.patient.dob == "1980-05-15"
    assert len(result.service_lines) == 2
    assert result.service_lines[0].procedure_code == "99213"
    assert result.service_lines[1].procedure_code == "87070"
    assert result.organization.name == "Brace Health Clinic"
    assert result.organization.contact.first_name == "Alice"
    assert result.organization.contact.last_name == "Smith"
    assert result.organization.contact.phone_number == "555-123-4567"


# E.g examples of testing validation logic
def test_invalid_gender_fails():
    claim = _valid_json()
    claim["patient"]["gender"] = "X"

    with pytest.raises(ValueError, match="Invalid gender: X"):
        json_to_payer_claim(claim)


def test_invalid_date_fails():
    claim = _valid_json()
    claim["patient"]["dob"] = "01-01-1990"

    with pytest.raises(ValueError, match="Invalid date: 01-01-1990"):
        json_to_payer_claim(claim)


def test_missing_field_fails():
    claim = _valid_json()
    del claim["claim_id"]

    with pytest.raises(KeyError):
        json_to_payer_claim(claim)

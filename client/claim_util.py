from generated import payer_claim_pb2, common_pb2


def json_to_claim(j: dict) -> payer_claim_pb2.PayerClaim:
    def _to_currency_value(units: int, currency: str, unit_charge_amount: float):
        if currency != "USD":
            raise ValueError("Only USD supported")
        total = units * unit_charge_amount
        return common_pb2.CurrencyValue(
            decimal_amount=int(round(total * 100)), whole_amount=int(total)
        )

    gender_map = {"m": payer_claim_pb2.Gender.M, "f": payer_claim_pb2.Gender.F}

    insurance = payer_claim_pb2.Insurance(
        payer_id=payer_claim_pb2.PayerId.Value(j["insurance"]["payer_id"].upper()),
        patient_member_id=j["insurance"]["patient_member_id"],
    )
    p = j["patient"]
    patient = payer_claim_pb2.Patient(
        first_name=p["first_name"],
        last_name=p["last_name"],
        gender=gender_map[p["gender"].lower()],
        dob=p["dob"],
    )
    org = payer_claim_pb2.Organization(name=j["organization"]["name"])
    provider = payer_claim_pb2.RenderingProvider(
        first_name=j["rendering_provider"]["first_name"],
        last_name=j["rendering_provider"]["last_name"],
        npi=j["rendering_provider"]["npi"],
    )
    service_lines = []
    for sl in j["service_lines"]:
        service_lines.append(
            payer_claim_pb2.ServiceLine(
                service_line_id=sl["service_line_id"],
                procedure_code=sl["procedure_code"],
                details=sl["details"],
                charge=_to_currency_value(
                    sl["units"], sl["unit_charge_currency"], sl["unit_charge_amount"]
                ),
            )
        )
    return payer_claim_pb2.PayerClaim(
        claim_id=j["claim_id"],
        place_of_service_code=j["place_of_service_code"],
        insurance=insurance,
        patient=patient,
        organization=org,
        rendering_provider=provider,
        service_lines=service_lines,
    )

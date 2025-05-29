import json
import grpc
import datetime

from generated import billing_pb2, billing_pb2_grpc


def _get_gender_enum(gender: str) -> billing_pb2.Gender:
    if gender.lower() == "m":
        return billing_pb2.Gender.M
    if gender.lower() == "f":
        return billing_pb2.Gender.F
    raise ValueError(f"Invalid gender: {gender}")


def _validate_date(date: str) -> str:
    try:
        datetime.datetime.strptime(date, "%Y-%m-%d")
        return date
    except ValueError:
        raise ValueError(f"Invalid date: {date}")


def json_to_payer_claim(j: dict) -> billing_pb2.PayerClaim:
    payer_id_enum = billing_pb2.PayerId.Value(j["insurance"]["payer_id"].upper())
    insurance = billing_pb2.Insurance(
        payer_id=payer_id_enum,
        patient_member_id=j["insurance"]["patient_member_id"],
    )

    patient_addr = j["patient"].get("address", {})
    patient = billing_pb2.Patient(
        first_name=j["patient"]["first_name"],
        last_name=j["patient"]["last_name"],
        email=j["patient"].get("email", ""),
        gender=_get_gender_enum(j["patient"]["gender"]),
        dob=_validate_date(j["patient"]["dob"]),
        address=billing_pb2.Address(**patient_addr),
    )
    org_addr = j["organization"].get("address", {})
    org_contact = j["organization"].get("contact", {})
    organization = billing_pb2.Organization(
        name=j["organization"]["name"],
        billing_npi=j["organization"]["billing_npi"],
        ein=j["organization"]["ein"],
        contact=billing_pb2.Contact(**org_contact),
        address=billing_pb2.Address(**org_addr),
    )
    rendering_provider = billing_pb2.RenderingProvider(
        first_name=j["rendering_provider"]["first_name"],
        last_name=j["rendering_provider"]["last_name"],
        npi=j["rendering_provider"]["npi"],
    )
    service_lines = []
    for item in j["service_lines"]:
        service_line = billing_pb2.ServiceLine(
            service_line_id=item["service_line_id"],
            procedure_code=item["procedure_code"],
            modifiers=item.get("modifiers", []),
            units=item["units"],
            details=item["details"],
            unit_charge_currency=item["unit_charge_currency"],
            unit_charge_amount=item["unit_charge_amount"],
            do_not_bill=item["do_not_bill"],
        )
        service_lines.append(service_line)
    return billing_pb2.PayerClaim(
        claim_id=j["claim_id"],
        place_of_service_code=j["place_of_service_code"],
        insurance=insurance,
        patient=patient,
        organization=organization,
        rendering_provider=rendering_provider,
        service_lines=service_lines,
    )


def run():
    # Load your JSON claim from file (or other source)
    with open("claim.json") as f:
        claim_json = json.load(f)

    # Convert to gRPC PayerClaim message
    claim_msg = json_to_payer_claim(claim_json)

    # Wrap in SubmitClaimRequest and send over gRPC
    with grpc.insecure_channel("localhost:9090") as channel:
        stub = billing_pb2_grpc.BillingServiceStub(channel)
        request = billing_pb2.SubmitClaimRequest(claim=claim_msg)
        response = stub.submitClaim(request)
        if response.success:
            print("Claim submitted successfully!")
        else:
            print("Claim submission failed.")


if __name__ == "__main__":
    run()

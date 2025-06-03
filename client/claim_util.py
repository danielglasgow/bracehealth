import random
from generated import payer_claim_pb2, common_pb2
from datetime import date, timedelta, datetime
import string


def _get_gender_enum(gender: str) -> payer_claim_pb2.Gender:
    mapping = {"m": payer_claim_pb2.Gender.M, "f": payer_claim_pb2.Gender.F}
    try:
        return mapping[gender.lower()]
    except KeyError:
        raise ValueError(f"Invalid gender: {gender}")


def _validate_date(date: str) -> str:
    try:
        datetime.strptime(date, "%Y-%m-%d")
        return date
    except ValueError:
        raise ValueError(f"Invalid date: {date}")


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
        gender=_get_gender_enum(p["gender"]),
        dob=_validate_date(p["dob"]),
    )
    org = payer_claim_pb2.Organization(
        name=j["organization"]["name"],
        billing_npi=j["organization"]["billing_npi"],
        ein=j["organization"]["ein"],
        contact=payer_claim_pb2.Contact(
            first_name=j["organization"]["contact"]["first_name"],
            last_name=j["organization"]["contact"]["last_name"],
            phone_number=j["organization"]["contact"]["phone_number"],
        ),
        address=payer_claim_pb2.Address(
            street=j["organization"]["address"]["street"],
            city=j["organization"]["address"]["city"],
            state=j["organization"]["address"]["state"],
            zip=j["organization"]["address"]["zip"],
            country=j["organization"]["address"]["country"],
        ),
    )
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


PAYERS = ["medicare", "united_health_group", "anthem"]
NAMES_F = [
    "Jane Smith",
    "Emily Johnson",
    "Olivia Brown",
    "Sophia Garcia",
    "Emma Miller",
    "Ava Davis",
    "Isabella Wilson",
    "Sophia Anderson",
    "Mia Martinez",
    "Charlotte Rodriguez",
    "Amelia Hernandez",
    "Harper Lopez",
    "Evelyn Gonzalez",
    "Abigail Wilson",
    "Liam Davis",
    "Noah Wilson",
    "Lucas Anderson",
    "Jackson Martinez",
    "Aiden Rodriguez",
    "Oliver Hernandez",
    "Caden Lopez",
    "Mateo Wilson",
    "Oliver Anderson",
    "Caden Martinez",
    "Mateo Rodriguez",
    "Oliver Hernandez",
    "Caden Lopez",
    "Mateo Wilson",
]


NAMES_M = [
    "Eliot Smith",
    "John Billings",
    "Michael Johnson",
    "James Smith",
    "David Brown",
    "Robert Garcia",
    "William Miller",
    "Joseph Davis",
    "Thomas Wilson",
    "Charles Anderson",
    "Christopher Martinez",
    "Daniel Rodriguez",
    "Anthony Hernandez",
    "Mark Lopez",
    "Donald Gonzalez",
    "Paul Wilson",
    "Steven Taylor",
    "Andrew Martinez",
    "Edward Rodriguez",
    "Brian Hernandez",
    "Ronald Lopez",
    "Timothy Gonzalez",
    "Jason Wilson",
    "Jeffrey Martinez",
    "Ryan Rodriguez",
    "Kevin Hernandez",
    "Steven Lopez",
    "Richard Gonzalez",
    "Paul Wilson",
    "Steven Taylor",
]
CITIES: list[tuple[str, str, str]] = [
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

# Make sure we always use the same patient details per first/last combo
patients: dict[str, dict] = {}


def _rand_date(start_year=1950, end_year=2005) -> str:
    start = date(start_year, 1, 1)
    delta = date(end_year, 12, 31) - start
    return (start + timedelta(days=random.randint(0, delta.days))).isoformat()


def _rand_digits(n: int) -> str:
    return "".join(random.choices(string.digits, k=n))


def _rand_email(first: str, last: str) -> str:
    return f"{first.lower()}.{last.lower()}@{random.choice(['example.com','mail.com','health.org'])}"


def _random_service_line(idx: int) -> dict:
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


def generate_random_claim() -> dict:
    gender = random.choice(["m", "f"])
    # Relatively small set of names so we can have multiple claims per patient
    name = random.choice(NAMES_F if gender == "f" else NAMES_M)
    first_name, last_name = name.split(" ")
    city, state, zip_code = random.choice(CITIES)

    patient = patients.get(f"{first_name}_{last_name}")
    if patient is None:
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
        patients[f"{first_name}_{last_name}"] = patient

    organization = {
        "name": random.choice(CLINIC_NAMES),
        "billing_npi": _rand_digits(10),
        "ein": f"{_rand_digits(2)}-{_rand_digits(7)}",
        "contact": {
            "first_name": random.choice(NAMES_F + NAMES_M).split(" ")[0],
            "last_name": random.choice(NAMES_F + NAMES_M).split(" ")[1],
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
        "first_name": random.choice(NAMES_F + NAMES_M).split(" ")[0],
        "last_name": random.choice(NAMES_F + NAMES_M).split(" ")[1],
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

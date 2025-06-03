import grpc
from typing import List, Sequence, Tuple, Optional, Union

# ─────────────────────────── generated stubs ────────────────────────── #
from generated import (
    billing_service_pb2 as bs_pb2,
    billing_service_pb2_grpc as bs_pb2_grpc,
    payer_claim_pb2 as pc_pb2,
    common_pb2,
)


SERVER_ADDR = "localhost:9090"


class BillingClient:
    def __init__(self, target: str = SERVER_ADDR):
        self.target = target
        self.channel = grpc.insecure_channel(target)
        self.stub = bs_pb2_grpc.BillingServiceStub(self.channel)

    def submit_claim(
        self, claim: pc_pb2.PayerClaim
    ) -> Union[bs_pb2.SubmitClaimResponse, grpc.RpcError]:
        try:
            return self.stub.submitClaim(
                bs_pb2.SubmitClaimRequest(claim=claim), timeout=5
            )
        except grpc.RpcError as e:
            return e

    def ar_by_payer(
        self,
        buckets: Sequence[Tuple[Optional[int], Optional[int]]],
        payer_ids: Sequence[int] | None = None,
    ) -> Union[bs_pb2.GetPayerAccountsReceivableResponse, grpc.RpcError]:
        try:
            req = bs_pb2.GetPayerAccountsReceivableRequest()
            for start, end in buckets:
                b = req.bucket.add()
            if start is not None:
                b.start_seconds_ago = start
            if end is not None:
                b.end_seconds_ago = end
            if payer_ids:
                req.payer_filter.extend(payer_ids)
            return self.stub.getPayerAccountsReceivable(req, timeout=3)
        except grpc.RpcError as e:
            return e

    def ar_by_patient(
        self, patient_ids: Sequence[str] | None = None
    ) -> Union[bs_pb2.GetPatientAccountsReceivableResponse, grpc.RpcError]:
        try:
            req = bs_pb2.GetPatientAccountsReceivableRequest()
            if patient_ids:
                req.patient_filter.extend(patient_ids)
            return self.stub.getPatientAccountsReceivable(req, timeout=3)
        except grpc.RpcError as e:
            return e

    def patient_claims(
        self, patient_id: str
    ) -> Union[bs_pb2.GetPatientClaimsResponse, grpc.RpcError]:
        try:
            req = bs_pb2.GetPatientClaimsRequest(patient_filter=patient_id)
            return self.stub.getPatientClaims(req, timeout=3)
        except grpc.RpcError as e:
            return e

    def pay_claim(
        self, claim_id: str, amt: common_pb2.CurrencyValue
    ) -> Union[bs_pb2.SubmitPatientPaymentResponse, grpc.RpcError]:
        try:
            req = bs_pb2.SubmitPatientPaymentRequest(claim_id=claim_id, amount=amt)
            return self.stub.submitPatientPayment(req, timeout=5)
        except grpc.RpcError as e:
            return e

    def list_patients(self) -> Union[dict[str, pc_pb2.Patient], grpc.RpcError]:
        """
        Placeholder until `getPatients` RPC exists.  For now, derive patient IDs
        from `getPatientAccountsReceivable`.
        """
        try:
            resp = self.ar_by_patient()
            ids: list[str] = []
            patients: dict[str, pc_pb2.Patient] = {}
            for row in resp.row:
                id = f"{row.patient.first_name.lower()}_{row.patient.last_name.lower()}_{row.patient.dob.lower()}"
                ids.append(id)
                patients[id] = row.patient
            return {id: patients[id] for id in sorted(set(ids))}
        except grpc.RpcError as e:
            return e

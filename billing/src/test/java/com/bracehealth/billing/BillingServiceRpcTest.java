package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.bracehealth.shared.*;
import com.bracehealth.shared.GetPatientClaimsResponse.ClaimStatus;
import com.bracehealth.shared.GetPatientClaimsResponse.GetPatientClaimError;
import com.bracehealth.shared.GetPatientClaimsResponse.PatientClaimRow;
import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.SubmitClaimResponse;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.billing.CurrencyUtil.CurrencyAmount;
import com.bracehealth.billing.PatientStore.PatientId;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;

class BillingServiceRpcTest {

    @TempDir
    Path tempDir;

    @Test
    void getPatientClaims_noFilter_returnsError() throws Exception {
        BillingService billingService = createBillingService();
        GetPatientClaimsResponse response = executeGetPatientClaimsRequest(billingService, "");

        assertEquals(GetPatientClaimError.GET_PATIENT_CLAIM_ERROR_NO_PATIENT_FILTER_SPECIFIED,
                response.getError());
    }

    @Test
    void getPatientClaims_nonexistentPatient_returnsError() throws Exception {
        BillingService billingService = createBillingService();
        GetPatientClaimsResponse response =
                executeGetPatientClaimsRequest(billingService, "nonexistent");

        assertEquals(GetPatientClaimError.GET_PATIENT_CLAIM_ERROR_INVALID_PATIENT_ID,
                response.getError());
    }

    @Test
    void getPatientClaims_submittedClaim_returnsSubmittedStatus() throws Exception {
        BillingService billingService = createBillingService();

        // Submit a claim
        Patient patient = createTestPatient("John", "Doe");
        PayerClaim claim = createTestClaim("C1", PayerId.MEDICARE, 100.0, patient);
        SubmitClaimRequest submitRequest = SubmitClaimRequest.newBuilder().setClaim(claim).build();
        SubmitClaimResponse submitResponse =
                executeSubmitClaimRequest(billingService, submitRequest);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse.getResult());

        // Get patient claims
        GetPatientClaimsResponse response =
                executeGetPatientClaimsRequest(billingService, PatientId.from(patient).toString());

        assertEquals(1, response.getRowCount());
        PatientClaimRow row = response.getRow(0);
        assertEquals(ClaimStatus.SUBMITTED_TO_PAYER, row.getStatus());
        assertEquals(claim.getClaimId(), row.getClaimId());
        assertEquals(patient, row.getPatient());
    }

    @Test
    void getPatientClaims_withRemittance_returnsRemittanceReceivedStatus() throws Exception {
        BillingService billingService = createBillingService();

        // Submit a claim
        Patient patient = createTestPatient("John", "Doe");
        PayerClaim claim = createTestClaim("C1", PayerId.MEDICARE, 100.0, patient);
        SubmitClaimRequest submitRequest = SubmitClaimRequest.newBuilder().setClaim(claim).build();
        SubmitClaimResponse submitResponse =
                executeSubmitClaimRequest(billingService, submitRequest);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse.getResult());

        // Submit remittance
        Remittance remittance = Remittance.newBuilder().setClaimId(claim.getClaimId())
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        NotifyRemittanceRequest remittanceRequest =
                NotifyRemittanceRequest.newBuilder().setRemittance(remittance).build();
        NotifyRemittanceResponse remittanceResponse =
                executeNotifyRemittanceRequest(billingService, remittanceRequest);
        assertEquals(
                NotifyRemittanceResponse.NotifyRemittanceResult.NOTIFY_REMITTANCE_RESULT_SUCCESS,
                remittanceResponse.getResult());

        // Get patient claims
        GetPatientClaimsResponse response =
                executeGetPatientClaimsRequest(billingService, PatientId.from(patient).toString());

        assertEquals(1, response.getRowCount());
        PatientClaimRow row = response.getRow(0);
        assertEquals(ClaimStatus.REMITTENCE_RECEIVED, row.getStatus());
        assertEquals(claim.getClaimId(), row.getClaimId());
        assertEquals(patient, row.getPatient());
        assertEquals(CurrencyAmount.from("10.00").toProto(),
                row.getBalance().getOutstandingCopay());
        assertEquals(CurrencyAmount.from("5.00").toProto(),
                row.getBalance().getOutstandingCoinsurance());
        assertEquals(CurrencyAmount.from("5.00").toProto(),
                row.getBalance().getOutstandingDeductible());
    }

    @Test
    void getPatientClaims_withFullPayment_returnsFullyPaidStatus() throws Exception {
        BillingService billingService = createBillingService();

        // Submit a claim
        Patient patient = createTestPatient("John", "Doe");
        PayerClaim claim = createTestClaim("C1", PayerId.MEDICARE, 100.0, patient);
        SubmitClaimRequest submitRequest = SubmitClaimRequest.newBuilder().setClaim(claim).build();
        SubmitClaimResponse submitResponse =
                executeSubmitClaimRequest(billingService, submitRequest);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse.getResult());

        // Submit remittance
        Remittance remittance = Remittance.newBuilder().setClaimId(claim.getClaimId())
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        NotifyRemittanceRequest remittanceRequest =
                NotifyRemittanceRequest.newBuilder().setRemittance(remittance).build();
        NotifyRemittanceResponse remittanceResponse =
                executeNotifyRemittanceRequest(billingService, remittanceRequest);
        assertEquals(
                NotifyRemittanceResponse.NotifyRemittanceResult.NOTIFY_REMITTANCE_RESULT_SUCCESS,
                remittanceResponse.getResult());

        // Submit patient payment
        SubmitPatientPaymentRequest paymentRequest =
                SubmitPatientPaymentRequest.newBuilder().setClaimId(claim.getClaimId())
                        .setAmount(CurrencyAmount.from("20.00").toProto()).build();
        SubmitPatientPaymentResponse paymentResponse =
                executeSubmitPatientPaymentRequest(billingService, paymentRequest);
        assertEquals(
                SubmitPatientPaymentResponse.SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FULLY_PAID,
                paymentResponse.getResult());

        // Get patient claims
        GetPatientClaimsResponse response =
                executeGetPatientClaimsRequest(billingService, PatientId.from(patient).toString());

        assertEquals(1, response.getRowCount());
        PatientClaimRow row = response.getRow(0);
        assertEquals(ClaimStatus.FULLY_PAID, row.getStatus());
        assertEquals(claim.getClaimId(), row.getClaimId());
        assertEquals(patient, row.getPatient());
        assertEquals(CurrencyAmount.from("0.00").toProto(), row.getBalance().getOutstandingCopay());
        assertEquals(CurrencyAmount.from("0.00").toProto(),
                row.getBalance().getOutstandingCoinsurance());
        assertEquals(CurrencyAmount.from("0.00").toProto(),
                row.getBalance().getOutstandingDeductible());
    }

    private BillingService createBillingService() {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("claims.json"));
        PatientStore patientStore = new PatientStore();
        return new BillingService(new PayerPaymentHelper(claimStore),
                new PatientPaymentHelper(claimStore, patientStore), patientStore, claimStore,
                new SuccessClearingHouseClient());
    }

    private Patient createTestPatient(String firstName, String lastName) {
        return Patient.newBuilder().setFirstName(firstName).setLastName(lastName)
                .setEmail(firstName.toLowerCase() + "." + lastName.toLowerCase() + "@example.com")
                .setGender(Gender.M).setDob("1980-01-01").build();
    }

    private PayerClaim createTestClaim(String claimId, PayerId payerId, double amount,
            Patient patient) {
        return PayerClaim.newBuilder().setClaimId(claimId).setPatient(patient)
                .setInsurance(Insurance.newBuilder().setPayerId(payerId).setPatientMemberId("PM123")
                        .build())
                .addServiceLines(ServiceLine.newBuilder().setServiceLineId("SL1")
                        .setProcedureCode("99213")
                        .setCharge(CurrencyAmount.from(BigDecimal.valueOf(amount)).toProto())
                        .setDoNotBill(false).build())
                .build();
    }

    private GetPatientClaimsResponse executeGetPatientClaimsRequest(BillingService billingService,
            String patientFilter) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetPatientClaimsResponse[] responseHolder = new GetPatientClaimsResponse[1];

        billingService.getPatientClaims(
                GetPatientClaimsRequest.newBuilder().setPatientFilter(patientFilter).build(),
                new StreamObserver<GetPatientClaimsResponse>() {
                    @Override
                    public void onNext(GetPatientClaimsResponse response) {
                        responseHolder[0] = response;
                    }

                    @Override
                    public void onError(Throwable t) {
                        throw new RuntimeException(t);
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request timed out");
        return responseHolder[0];
    }

    private SubmitClaimResponse executeSubmitClaimRequest(BillingService billingService,
            SubmitClaimRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SubmitClaimResponse[] responseHolder = new SubmitClaimResponse[1];

        billingService.submitClaim(request, new StreamObserver<SubmitClaimResponse>() {
            @Override
            public void onNext(SubmitClaimResponse response) {
                responseHolder[0] = response;
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request timed out");
        return responseHolder[0];
    }

    private NotifyRemittanceResponse executeNotifyRemittanceRequest(BillingService billingService,
            NotifyRemittanceRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        NotifyRemittanceResponse[] responseHolder = new NotifyRemittanceResponse[1];

        billingService.notifyRemittance(request, new StreamObserver<NotifyRemittanceResponse>() {
            @Override
            public void onNext(NotifyRemittanceResponse response) {
                responseHolder[0] = response;
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request timed out");
        return responseHolder[0];
    }

    private SubmitPatientPaymentResponse executeSubmitPatientPaymentRequest(
            BillingService billingService, SubmitPatientPaymentRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SubmitPatientPaymentResponse[] responseHolder = new SubmitPatientPaymentResponse[1];

        billingService.submitPatientPayment(request,
                new StreamObserver<SubmitPatientPaymentResponse>() {
                    @Override
                    public void onNext(SubmitPatientPaymentResponse response) {
                        responseHolder[0] = response;
                    }

                    @Override
                    public void onError(Throwable t) {
                        throw new RuntimeException(t);
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request timed out");
        return responseHolder[0];
    }

    private static class SuccessClearingHouseClient implements ClearingHouseClient {
        @Override
        public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
            return ProcessClaimResponse.newBuilder().setSuccess(true).build();
        }
    }
}

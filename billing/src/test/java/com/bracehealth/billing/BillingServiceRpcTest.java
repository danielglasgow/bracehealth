package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.bracehealth.shared.*;
import com.bracehealth.shared.GetPatientClaimsResponse.ClaimStatus;
import com.bracehealth.shared.GetPatientClaimsResponse.GetPatientClaimError;
import com.bracehealth.shared.GetPatientClaimsResponse.PatientClaimRow;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableBucketValue;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableRow;
import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.SubmitClaimResponse;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import com.bracehealth.billing.PatientStore.PatientId;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void getPayerAccountsReceivable_withTimeBuckets_returnsCorrectAmounts() throws Exception {
        BillingService billingService = createBillingService();

        // Create test patient and claims
        Patient patient = createTestPatient("John", "Doe");
        PayerClaim claim1 = createTestClaim("C1", PayerId.MEDICARE, 20.0, patient);
        PayerClaim claim2 = createTestClaim("C2", PayerId.MEDICARE, 30.0, patient);
        PayerClaim claim3 = createTestClaim("C3", PayerId.MEDICARE, 40.0, patient);
        PayerClaim claim4 = createTestClaim("C4", PayerId.MEDICARE, 10.0, patient);

        long oneSecondAgo = System.currentTimeMillis() / 1000 - 1;
        long fifteenSecondsAgo = System.currentTimeMillis() / 1000 - 15;
        long twentyFiveSecondsAgo = System.currentTimeMillis() / 1000 - 25;
        long fourtySecondsAgo = System.currentTimeMillis() / 1000 - 40;

        SubmitClaimRequest submitRequest1 = SubmitClaimRequest.newBuilder().setClaim(claim1)
                .setSendTimeSeconds(oneSecondAgo).build();
        SubmitClaimResponse submitResponse1 =
                executeSubmitClaimRequest(billingService, submitRequest1);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse1.getResult());

        SubmitClaimRequest submitRequest2 = SubmitClaimRequest.newBuilder().setClaim(claim2)
                .setSendTimeSeconds(fifteenSecondsAgo).build();
        SubmitClaimResponse submitResponse2 =
                executeSubmitClaimRequest(billingService, submitRequest2);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse2.getResult());

        SubmitClaimRequest submitRequest3 = SubmitClaimRequest.newBuilder().setClaim(claim3)
                .setSendTimeSeconds(twentyFiveSecondsAgo).build();
        SubmitClaimResponse submitResponse3 =
                executeSubmitClaimRequest(billingService, submitRequest3);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse3.getResult());

        SubmitClaimRequest submitRequest4 = SubmitClaimRequest.newBuilder().setClaim(claim4)
                .setSendTimeSeconds(fourtySecondsAgo).build();
        SubmitClaimResponse submitResponse4 =
                executeSubmitClaimRequest(billingService, submitRequest4);
        assertEquals(SubmitClaimResponse.SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS,
                submitResponse4.getResult());

        // Get accounts receivable with time buckets
        GetPayerAccountsReceivableRequest request = GetPayerAccountsReceivableRequest.newBuilder()
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(10)
                        .setEndSecondsAgo(0).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(20)
                        .setEndSecondsAgo(10).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(30)
                        .setEndSecondsAgo(20).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(0)
                        .setEndSecondsAgo(30).build())
                .addPayerFilter(PayerId.MEDICARE).build();

        GetPayerAccountsReceivableResponse response =
                executeGetPayerAccountsReceivableRequest(billingService, request);

        // Verify response
        assertEquals(1, response.getRowCount());
        GetPayerAccountsReceivableResponse.AccountsReceivableRow row = response.getRow(0);
        assertEquals(PayerId.MEDICARE.toString(), row.getPayerId());
        assertEquals(4, row.getBucketValueCount());

        var bucket0to10 = getBucketValue(row, 10, 0);
        assertEquals(20, bucket0to10.getAmount().getWholeAmount());
        assertEquals(0, bucket0to10.getAmount().getDecimalAmount());

        var bucket10to20 = getBucketValue(row, 20, 10);
        assertEquals(30, bucket10to20.getAmount().getWholeAmount());
        assertEquals(0, bucket10to20.getAmount().getDecimalAmount());

        var bucket20to30 = getBucketValue(row, 30, 20);
        assertEquals(40, bucket20to30.getAmount().getWholeAmount());
        assertEquals(0, bucket20to30.getAmount().getDecimalAmount());

        var bucket30plus = getBucketValue(row, 0, 30);
        assertEquals(10, bucket30plus.getAmount().getWholeAmount());
        assertEquals(0, bucket30plus.getAmount().getDecimalAmount());
    }

    private AccountsReceivableBucketValue getBucketValue(AccountsReceivableRow row,
            int startSecondsAgo, int endSecondsAgo) {
        return row.getBucketValueList().stream()
                .filter(bv -> bv.getBucket().getStartSecondsAgo() == startSecondsAgo
                        && bv.getBucket().getEndSecondsAgo() == endSecondsAgo)
                .findFirst().orElseThrow(() -> new AssertionError("No bucket found for range "
                        + startSecondsAgo + "-" + endSecondsAgo + " seconds ago"));
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

    private GetPayerAccountsReceivableResponse executeGetPayerAccountsReceivableRequest(
            BillingService billingService, GetPayerAccountsReceivableRequest request)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetPayerAccountsReceivableResponse[] responseHolder =
                new GetPayerAccountsReceivableResponse[1];

        billingService.getPayerAccountsReceivable(request,
                new StreamObserver<GetPayerAccountsReceivableResponse>() {
                    @Override
                    public void onNext(GetPayerAccountsReceivableResponse response) {
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

package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bracehealth.shared.*;
import com.google.common.collect.ImmutableMap;
import io.grpc.stub.StreamObserver;

class BillingServiceImplTest {

    @TempDir
    Path tempDir;

    private final ClearingHouseClient clearingHouseClient = new SuccessClearingHouseClient();

    @Test
    void getAccountsReceivable_singleBucket() throws Exception {
        Instant now = Instant.now();
        Instant oneMinAgo = now.minusSeconds(60);
        Instant twoMinAgo = now.minusSeconds(120);

        ClaimStore.Claim claim1 = createPendingClaim(
                getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).build(), now);
        ClaimStore.Claim claim2 = createPendingClaim(
                getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).build(), oneMinAgo);
        ClaimStore.Claim claim3 = createPendingClaim(
                getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0).build(), twoMinAgo);
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"),
                ImmutableMap.of("C1", claim1, "C2", claim2, "C3", claim3));

        GetAccountsReceivableRequest request =
                GetAccountsReceivableRequest.newBuilder().addBucket(AccountsReceivableBucket
                        .newBuilder().setStartSecondsAgo(180).setEndSecondsAgo(0).build()).build();

        GetAccountsReceivableResponse response = executeRequest(claimStore, request);

        assertEquals(2, response.getRowCount(), "Should have 2 rows (one per payer)");

        AccountsReceivableRow medicareRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.MEDICARE.name())).findFirst()
                .orElseThrow();

        assertEquals(1, medicareRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(300.0, medicareRow.getBucketValue(0).getAmount(), 0.001,
                "Medicare total should be 300");

        AccountsReceivableRow uhgRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.UNITED_HEALTH_GROUP.name()))
                .findFirst().orElseThrow();

        assertEquals(1, uhgRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(150.0, uhgRow.getBucketValue(0).getAmount(), 0.001, "UHG total should be 150");
    }


    // I don't love this test, but it's quick and dirty
    @Test
    void getAccountsReceivable_multipleBuckets() throws Exception {
        Instant now = Instant.now();
        Instant thirtySecAgo = now.minusSeconds(30);
        Instant ninetySecAgo = now.minusSeconds(90);
        Instant twoMinAgo = now.minusSeconds(120);
        Instant threeMinAgo = now.minusSeconds(180);
        Instant fourMinAgo = now.minusSeconds(240);

        // Medicare claims in different buckets - multiple entries per bucket
        ClaimStore.Claim medicareNow1 = createPendingClaim(
                getPayerClaimBuilder("M1", PayerId.MEDICARE, 100.0).build(), now);
        ClaimStore.Claim medicareNow2 = createPendingClaim(
                getPayerClaimBuilder("M1a", PayerId.MEDICARE, 50.0).build(), thirtySecAgo);
        ClaimStore.Claim medicareOneMin1 = createPendingClaim(
                getPayerClaimBuilder("M2", PayerId.MEDICARE, 200.0).build(), ninetySecAgo);
        ClaimStore.Claim medicareOneMin2 = createPendingClaim(
                getPayerClaimBuilder("M2a", PayerId.MEDICARE, 75.0).build(), ninetySecAgo);
        ClaimStore.Claim medicareTwoMin1 = createPendingClaim(
                getPayerClaimBuilder("M3", PayerId.MEDICARE, 300.0).build(), twoMinAgo);
        ClaimStore.Claim medicareTwoMin2 = createPendingClaim(
                getPayerClaimBuilder("M3a", PayerId.MEDICARE, 125.0).build(), twoMinAgo);
        ClaimStore.Claim medicareThreePlus1 = createPendingClaim(
                getPayerClaimBuilder("M4", PayerId.MEDICARE, 400.0).build(), fourMinAgo);
        ClaimStore.Claim medicareThreePlus2 = createPendingClaim(
                getPayerClaimBuilder("M4a", PayerId.MEDICARE, 150.0).build(), threeMinAgo);

        // UHG claims in different buckets - multiple entries per bucket
        ClaimStore.Claim uhgNow1 = createPendingClaim(
                getPayerClaimBuilder("U1", PayerId.UNITED_HEALTH_GROUP, 150.0).build(), now);
        ClaimStore.Claim uhgNow2 = createPendingClaim(
                getPayerClaimBuilder("U1a", PayerId.UNITED_HEALTH_GROUP, 75.0).build(),
                thirtySecAgo);
        ClaimStore.Claim uhgOneMin1 = createPendingClaim(
                getPayerClaimBuilder("U2", PayerId.UNITED_HEALTH_GROUP, 250.0).build(),
                ninetySecAgo);
        ClaimStore.Claim uhgOneMin2 = createPendingClaim(
                getPayerClaimBuilder("U2a", PayerId.UNITED_HEALTH_GROUP, 100.0).build(),
                ninetySecAgo);
        ClaimStore.Claim uhgTwoMin1 = createPendingClaim(
                getPayerClaimBuilder("U3", PayerId.UNITED_HEALTH_GROUP, 350.0).build(), twoMinAgo);
        ClaimStore.Claim uhgTwoMin2 = createPendingClaim(
                getPayerClaimBuilder("U3a", PayerId.UNITED_HEALTH_GROUP, 125.0).build(), twoMinAgo);
        ClaimStore.Claim uhgThreePlus1 = createPendingClaim(
                getPayerClaimBuilder("U4", PayerId.UNITED_HEALTH_GROUP, 450.0).build(),
                threeMinAgo);
        ClaimStore.Claim uhgThreePlus2 = createPendingClaim(
                getPayerClaimBuilder("U4a", PayerId.UNITED_HEALTH_GROUP, 175.0).build(),
                threeMinAgo);

        // Anthem claims in different buckets - multiple entries per bucket
        ClaimStore.Claim anthemNow1 =
                createPendingClaim(getPayerClaimBuilder("A1", PayerId.ANTHEM, 175.0).build(), now);
        ClaimStore.Claim anthemNow2 = createPendingClaim(
                getPayerClaimBuilder("A1a", PayerId.ANTHEM, 85.0).build(), thirtySecAgo);
        ClaimStore.Claim anthemOneMin1 = createPendingClaim(
                getPayerClaimBuilder("A2", PayerId.ANTHEM, 275.0).build(), ninetySecAgo);
        ClaimStore.Claim anthemOneMin2 = createPendingClaim(
                getPayerClaimBuilder("A2a", PayerId.ANTHEM, 125.0).build(), ninetySecAgo);
        ClaimStore.Claim anthemTwoMin1 = createPendingClaim(
                getPayerClaimBuilder("A3", PayerId.ANTHEM, 375.0).build(), twoMinAgo);
        ClaimStore.Claim anthemTwoMin2 = createPendingClaim(
                getPayerClaimBuilder("A3a", PayerId.ANTHEM, 150.0).build(), twoMinAgo);
        ClaimStore.Claim anthemThreePlus1 = createPendingClaim(
                getPayerClaimBuilder("A4", PayerId.ANTHEM, 475.0).build(), threeMinAgo);
        ClaimStore.Claim anthemThreePlus2 = createPendingClaim(
                getPayerClaimBuilder("A4a", PayerId.ANTHEM, 200.0).build(), threeMinAgo);

        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"),
                ImmutableMap.<String, ClaimStore.Claim>builder().put("M1", medicareNow1)
                        .put("M1a", medicareNow2).put("M2", medicareOneMin1)
                        .put("M2a", medicareOneMin2).put("M3", medicareTwoMin1)
                        .put("M3a", medicareTwoMin2).put("M4", medicareThreePlus1)
                        .put("M4a", medicareThreePlus2).put("U1", uhgNow1).put("U1a", uhgNow2)
                        .put("U2", uhgOneMin1).put("U2a", uhgOneMin2).put("U3", uhgTwoMin1)
                        .put("U3a", uhgTwoMin2).put("U4", uhgThreePlus1).put("U4a", uhgThreePlus2)
                        .put("A1", anthemNow1).put("A1a", anthemNow2).put("A2", anthemOneMin1)
                        .put("A2a", anthemOneMin2).put("A3", anthemTwoMin1)
                        .put("A3a", anthemTwoMin2).put("A4", anthemThreePlus1)
                        .put("A4a", anthemThreePlus2).build());

        GetAccountsReceivableRequest request = GetAccountsReceivableRequest.newBuilder()
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(60)
                        .setEndSecondsAgo(0).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(120)
                        .setEndSecondsAgo(60).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(180)
                        .setEndSecondsAgo(120).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setEndSecondsAgo(180).build())
                .build();

        GetAccountsReceivableResponse response = executeRequest(claimStore, request);

        assertEquals(3, response.getRowCount(), "Should have 3 rows (one per payer)");

        // Verify Medicare amounts
        AccountsReceivableRow medicareRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.MEDICARE.name())).findFirst()
                .orElseThrow();
        assertEquals(4, medicareRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(150.0, medicareRow.getBucketValue(0).getAmount(), 0.001,
                "Medicare 0-1min should be 150 (100 + 50)");
        assertEquals(275.0, medicareRow.getBucketValue(1).getAmount(), 0.001,
                "Medicare 1-2min should be 275 (200 + 75)");
        assertEquals(425.0, medicareRow.getBucketValue(2).getAmount(), 0.001,
                "Medicare 2-3min should be 425 (300 + 125)");
        assertEquals(550.0, medicareRow.getBucketValue(3).getAmount(), 0.001,
                "Medicare 3+min should be 550 (400 + 150)");

        // Verify UHG amounts
        AccountsReceivableRow uhgRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.UNITED_HEALTH_GROUP.name()))
                .findFirst().orElseThrow();
        assertEquals(4, uhgRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(225.0, uhgRow.getBucketValue(0).getAmount(), 0.001,
                "UHG 0-1min should be 225 (150 + 75)");
        assertEquals(350.0, uhgRow.getBucketValue(1).getAmount(), 0.001,
                "UHG 1-2min should be 350 (250 + 100)");
        assertEquals(475.0, uhgRow.getBucketValue(2).getAmount(), 0.001,
                "UHG 2-3min should be 475 (350 + 125)");
        assertEquals(625.0, uhgRow.getBucketValue(3).getAmount(), 0.001,
                "UHG 3+min should be 625 (450 + 175)");

        // Verify Anthem amounts
        AccountsReceivableRow anthemRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.ANTHEM.name())).findFirst()
                .orElseThrow();
        assertEquals(4, anthemRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(260.0, anthemRow.getBucketValue(0).getAmount(), 0.001,
                "Anthem 0-1min should be 260 (175 + 85)");
        assertEquals(400.0, anthemRow.getBucketValue(1).getAmount(), 0.001,
                "Anthem 1-2min should be 400 (275 + 125)");
        assertEquals(525.0, anthemRow.getBucketValue(2).getAmount(), 0.001,
                "Anthem 2-3min should be 525 (375 + 150)");
        assertEquals(675.0, anthemRow.getBucketValue(3).getAmount(), 0.001,
                "Anthem 3+min should be 675 (475 + 200)");
    }

    @Test
    void submitClaim_duplicateHandling() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        SubmitClaimRequest request = SubmitClaimRequest.newBuilder().setClaim(claim).build();

        SubmitClaimResponse response = executeSubmitClaimRequest(claimStore, request);
        assertEquals(SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS, response.getResult());

        response = executeSubmitClaimRequest(claimStore, request);
        assertEquals(SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED, response.getResult());
    }

    @Test
    void getPatientAccountsReceivable() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());

        PayerClaim johnClaim1 =
                getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim1);
        PayerClaim johnClaim2 =
                getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim2);
        PayerClaim janeClaim = getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0)
                .setPatient(Patient.newBuilder().setFirstName("Jane").setLastName("Smith")
                        .setEmail("jane@example.com").setGender(Gender.F).setDob("1985-01-01")
                        .build())
                .build();
        claimStore.addClaim(janeClaim);

        RemittanceResponse remittanceResponse = RemittanceResponse.newBuilder().setClaimId("C1")
                .setPayerPaidAmount(80.0).setCopayAmount(10.0).setCoinsuranceAmount(5.0)
                .setDeductibleAmount(5.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("C1", remittanceResponse);

        GetPatientAccountsReceivableResponse response =
                executePatientAccountsReceivableRequest(claimStore);

        assertEquals(2, response.getRowCount(), "Should have 2 rows (one per patient)");
        PatientAccountsReceivableRow johnRow =
                response.getRowList().stream()
                        .filter(row -> row.getPatient().getFirstName().equals("John")
                                && row.getPatient().getLastName().equals("Doe"))
                        .findFirst().orElseThrow();
        assertEquals(10.0, johnRow.getOutstandingCopay(), 0.001, "John's copay should be 10.0");
        assertEquals(5.0, johnRow.getOutstandingCoinsurance(), 0.001,
                "John's coinsurance should be 5.0");
        assertEquals(5.0, johnRow.getOutstandingDeductible(), 0.001,
                "John's deductible should be 5.0");
        PatientAccountsReceivableRow janeRow = response.getRowList().stream()
                .filter(row -> row.getPatient().getFirstName().equals("Jane")
                        && row.getPatient().getLastName().equals("Smith"))
                .findFirst().orElseThrow();
        assertEquals(0.0, janeRow.getOutstandingCopay(), 0.001, "Jane's copay should be 0.0");
        assertEquals(0.0, janeRow.getOutstandingCoinsurance(), 0.001,
                "Jane's coinsurance should be 0.0");
        assertEquals(0.0, janeRow.getOutstandingDeductible(), 0.001,
                "Jane's deductible should be 0.0");
    }

    @Test
    void getPatientAccountsReceivable_allResponsesReceived() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());

        PayerClaim johnClaim1 =
                getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim1);
        PayerClaim johnClaim2 =
                getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim2);
        PayerClaim janeClaim = getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0)
                .setPatient(Patient.newBuilder().setFirstName("Jane").setLastName("Smith")
                        .setEmail("jane@example.com").setGender(Gender.F).setDob("1985-01-01")
                        .build())
                .build();
        claimStore.addClaim(janeClaim);

        RemittanceResponse remittanceResponse = RemittanceResponse.newBuilder().setClaimId("C1")
                .setPayerPaidAmount(80.0).setCopayAmount(10.0).setCoinsuranceAmount(5.0)
                .setDeductibleAmount(5.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("C1", remittanceResponse);

        remittanceResponse = RemittanceResponse.newBuilder().setClaimId("C2")
                .setPayerPaidAmount(160.0).setCopayAmount(20.0).setCoinsuranceAmount(10.0)
                .setDeductibleAmount(10.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("C2", remittanceResponse);

        remittanceResponse = RemittanceResponse.newBuilder().setClaimId("C3")
                .setPayerPaidAmount(120.0).setCopayAmount(15.0).setCoinsuranceAmount(10.0)
                .setDeductibleAmount(5.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("C3", remittanceResponse);

        GetPatientAccountsReceivableResponse response =
                executePatientAccountsReceivableRequest(claimStore);

        assertEquals(2, response.getRowCount(), "Should have 2 rows (one per patient)");

        // For John:
        // - C1: 10.0 copay, 5.0 coinsurance, 5.0 deductible
        // - C2: 20.0 copay, 10.0 coinsurance, 10.0 deductible
        // Total: 30.0 copay, 15.0 coinsurance, 15.0 deductible
        PatientAccountsReceivableRow johnRow =
                response.getRowList().stream()
                        .filter(row -> row.getPatient().getFirstName().equals("John")
                                && row.getPatient().getLastName().equals("Doe"))
                        .findFirst().orElseThrow();
        assertEquals(30.0, johnRow.getOutstandingCopay(), 0.001,
                "John's copay should be 30.0 (10.0 + 20.0)");
        assertEquals(15.0, johnRow.getOutstandingCoinsurance(), 0.001,
                "John's coinsurance should be 15.0 (5.0 + 10.0)");
        assertEquals(15.0, johnRow.getOutstandingDeductible(), 0.001,
                "John's deductible should be 15.0 (5.0 + 10.0)");

        // For Jane:
        // - C3: 15.0 copay, 10.0 coinsurance, 5.0 deductible
        PatientAccountsReceivableRow janeRow = response.getRowList().stream()
                .filter(row -> row.getPatient().getFirstName().equals("Jane")
                        && row.getPatient().getLastName().equals("Smith"))
                .findFirst().orElseThrow();
        assertEquals(15.0, janeRow.getOutstandingCopay(), 0.001, "Jane's copay should be 15.0");
        assertEquals(10.0, janeRow.getOutstandingCoinsurance(), 0.001,
                "Jane's coinsurance should be 10.0");
        assertEquals(5.0, janeRow.getOutstandingDeductible(), 0.001,
                "Jane's deductible should be 5.0");
    }

    @Test
    void submitPatientPayment_success() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim);

        RemittanceResponse remittanceResponse = RemittanceResponse.newBuilder().setClaimId("TEST1")
                .setPayerPaidAmount(80.0).setCopayAmount(10.0).setCoinsuranceAmount(5.0)
                .setDeductibleAmount(5.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("TEST1", remittanceResponse);

        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("TEST1").setAmount(15.0).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_SUCCESS,
                response.getResult());
    }

    @Test
    void submitPatientPayment_claimNotFound() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());
        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("NONEXISTENT").setAmount(15.0).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FAILURE,
                response.getResult());
    }

    @Test
    void submitPatientPayment_noOutstandingBalance() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim);

        // No remittance response means no patient responsibility
        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("TEST1").setAmount(15.0).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE,
                response.getResult());
    }

    @Test
    void submitPatientPayment_amountExceedsBalance() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"), ImmutableMap.of());
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim);

        RemittanceResponse remittanceResponse = RemittanceResponse.newBuilder().setClaimId("TEST1")
                .setPayerPaidAmount(80.0).setCopayAmount(10.0).setCoinsuranceAmount(5.0)
                .setDeductibleAmount(5.0).setNotAllowedAmount(0.0).build();
        claimStore.addResponse("TEST1", remittanceResponse);

        SubmitPatientPaymentRequest request =
                SubmitPatientPaymentRequest.newBuilder().setClaimId("TEST1").setAmount(25.0) // Total
                                                                                             // patient
                                                                                             // responsibility
                                                                                             // is
                                                                                             // 20.0
                        .build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FAILURE,
                response.getResult());
    }

    private GetAccountsReceivableResponse executeRequest(ClaimStore claimStore,
            GetAccountsReceivableRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetAccountsReceivableResponse[] responseHolder = new GetAccountsReceivableResponse[1];
        BillingServiceImpl billingService = new BillingServiceImpl(claimStore, clearingHouseClient);
        billingService.getAccountsReceivable(request,
                new StreamObserver<GetAccountsReceivableResponse>() {
                    @Override
                    public void onNext(GetAccountsReceivableResponse response) {
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

    private SubmitClaimResponse executeSubmitClaimRequest(ClaimStore claimStore,
            SubmitClaimRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SubmitClaimResponse[] responseHolder = new SubmitClaimResponse[1];
        BillingServiceImpl billingService = new BillingServiceImpl(claimStore, clearingHouseClient);
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

    private GetPatientAccountsReceivableResponse executePatientAccountsReceivableRequest(
            ClaimStore claimStore) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetPatientAccountsReceivableResponse[] responseHolder =
                new GetPatientAccountsReceivableResponse[1];
        BillingServiceImpl billingService = new BillingServiceImpl(claimStore, clearingHouseClient);
        billingService.getPatientAccountsReceivable(
                GetPatientAccountsReceivableRequest.getDefaultInstance(),
                new StreamObserver<GetPatientAccountsReceivableResponse>() {
                    @Override
                    public void onNext(GetPatientAccountsReceivableResponse response) {
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

    private SubmitPatientPaymentResponse executeSubmitPatientPaymentRequest(ClaimStore claimStore,
            SubmitPatientPaymentRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SubmitPatientPaymentResponse[] responseHolder = new SubmitPatientPaymentResponse[1];
        BillingServiceImpl billingService = new BillingServiceImpl(claimStore, clearingHouseClient);
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

    private static ClaimStore.Claim createPendingClaim(PayerClaim claim, Instant submittedAt) {
        return new ClaimStore.Claim(claim, submittedAt, ClaimStore.ClaimStatus.PENDING,
                Optional.empty(), 0.0);
    }

    private static PayerClaim.Builder getPayerClaimBuilder(String claimId, PayerId payerId,
            double amount) {
        return PayerClaim.newBuilder().setClaimId(claimId)
                .setInsurance(Insurance.newBuilder().setPayerId(payerId).setPatientMemberId("PM123")
                        .build())
                .addServiceLines(ServiceLine.newBuilder().setServiceLineId("SL1")
                        .setProcedureCode("99213").setUnits(1).setUnitChargeAmount(amount)
                        .setUnitChargeCurrency("USD").setDoNotBill(false).build());
    }

    private static class SuccessClearingHouseClient implements ClearingHouseClient {
        @Override
        public ClearingHouseSubmitClaimResponse submitClaim(SubmitClaimRequest request) {
            return ClearingHouseSubmitClaimResponse.newBuilder().setSuccess(true).build();
        }
    }
}

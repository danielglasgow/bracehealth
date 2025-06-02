package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.bracehealth.shared.AccountsReceivableBucket;
import com.bracehealth.shared.GetPayerAccountsReceivableRequest;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableRow;
import com.bracehealth.shared.GetPatientAccountsReceivableRequest;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse.PatientAccountsReceivableRow;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.Patient;
import com.bracehealth.shared.SubmitPatientPaymentRequest;
import com.bracehealth.shared.SubmitPatientPaymentResponse;
import com.bracehealth.shared.SubmitPatientPaymentResponse.SubmitPatientPaymentResult;
import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.SubmitClaimResponse;
import com.bracehealth.shared.SubmitClaimResponse.SubmitClaimResult;
import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.PayerId;
import com.bracehealth.shared.ServiceLine;
import com.bracehealth.shared.Insurance;
import com.bracehealth.shared.Gender;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.grpc.stub.StreamObserver;

class BillingServiceTest {

    @TempDir
    Path tempDir;

    private final ClearingHouseClient clearingHouseClient = new SuccessClearingHouseClient();

    @Test
    void getAccountsReceivable_singleBucket() throws Exception {
        Instant now = Instant.now();
        Instant oneMinAgo = now.minusSeconds(60);
        Instant twoMinAgo = now.minusSeconds(120);

        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));

        PayerClaim claim1 = getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim1, now);
        PayerClaim claim2 = getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).build();
        claimStore.addClaim(claim2, oneMinAgo);
        PayerClaim claim3 = getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0).build();
        claimStore.addClaim(claim3, twoMinAgo);

        GetPayerAccountsReceivableRequest request =
                GetPayerAccountsReceivableRequest.newBuilder().addBucket(AccountsReceivableBucket
                        .newBuilder().setStartSecondsAgo(180).setEndSecondsAgo(0).build()).build();

        GetPayerAccountsReceivableResponse response = executeRequest(claimStore, request);

        assertEquals(3, response.getRowCount(), "Should have 3 rows (one per payer)");

        AccountsReceivableRow medicareRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.MEDICARE.name())).findFirst()
                .orElseThrow();

        assertEquals(1, medicareRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(CurrencyAmount.from("300.00").toProto(),
                medicareRow.getBucketValue(0).getAmount(), "Medicare total should be 300");

        AccountsReceivableRow uhgRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.UNITED_HEALTH_GROUP.name()))
                .findFirst().orElseThrow();

        assertEquals(1, uhgRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(CurrencyAmount.from("150.00").toProto(), uhgRow.getBucketValue(0).getAmount(),
                "UHG total should be 150");

        AccountsReceivableRow anthemRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.ANTHEM.name())).findFirst()
                .orElseThrow();

        assertEquals(1, anthemRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(CurrencyAmount.from("0.00").toProto(), anthemRow.getBucketValue(0).getAmount(),
                "Anthem total should be 0");
    }

    @Test
    void getAccountsReceivable_multipleBuckets() throws Exception {
        Instant now = Instant.now();
        Instant thirtySecAgo = now.minusSeconds(30);
        Instant ninetySecAgo = now.minusSeconds(90);
        Instant twoMinAgo = now.minusSeconds(120);
        Instant threeMinAgo = now.minusSeconds(180);
        Instant fourMinAgo = now.minusSeconds(240);

        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));

        // Medicare claims in different buckets - multiple entries per bucket
        PayerClaim medicareNow1 = getPayerClaimBuilder("M1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(medicareNow1, now);
        PayerClaim medicareNow2 = getPayerClaimBuilder("M1a", PayerId.MEDICARE, 50.0).build();
        claimStore.addClaim(medicareNow2, thirtySecAgo);
        PayerClaim medicareOneMin1 = getPayerClaimBuilder("M2", PayerId.MEDICARE, 200.0).build();
        claimStore.addClaim(medicareOneMin1, ninetySecAgo);
        PayerClaim medicareOneMin2 = getPayerClaimBuilder("M2a", PayerId.MEDICARE, 75.0).build();
        claimStore.addClaim(medicareOneMin2, ninetySecAgo);
        PayerClaim medicareTwoMin1 = getPayerClaimBuilder("M3", PayerId.MEDICARE, 300.0).build();
        claimStore.addClaim(medicareTwoMin1, twoMinAgo);
        PayerClaim medicareTwoMin2 = getPayerClaimBuilder("M3a", PayerId.MEDICARE, 125.0).build();
        claimStore.addClaim(medicareTwoMin2, twoMinAgo);
        PayerClaim medicareThreePlus1 = getPayerClaimBuilder("M4", PayerId.MEDICARE, 400.0).build();
        claimStore.addClaim(medicareThreePlus1, fourMinAgo);
        PayerClaim medicareThreePlus2 =
                getPayerClaimBuilder("M4a", PayerId.MEDICARE, 150.0).build();
        claimStore.addClaim(medicareThreePlus2, threeMinAgo);

        // UHG claims in different buckets - multiple entries per bucket
        PayerClaim uhgNow1 = getPayerClaimBuilder("U1", PayerId.UNITED_HEALTH_GROUP, 150.0).build();
        claimStore.addClaim(uhgNow1, now);
        PayerClaim uhgNow2 = getPayerClaimBuilder("U1a", PayerId.UNITED_HEALTH_GROUP, 75.0).build();
        claimStore.addClaim(uhgNow2, thirtySecAgo);
        PayerClaim uhgOneMin1 =
                getPayerClaimBuilder("U2", PayerId.UNITED_HEALTH_GROUP, 250.0).build();
        claimStore.addClaim(uhgOneMin1, ninetySecAgo);
        PayerClaim uhgOneMin2 =
                getPayerClaimBuilder("U2a", PayerId.UNITED_HEALTH_GROUP, 100.0).build();
        claimStore.addClaim(uhgOneMin2, ninetySecAgo);
        PayerClaim uhgTwoMin1 =
                getPayerClaimBuilder("U3", PayerId.UNITED_HEALTH_GROUP, 350.0).build();
        claimStore.addClaim(uhgTwoMin1, twoMinAgo);
        PayerClaim uhgTwoMin2 =
                getPayerClaimBuilder("U3a", PayerId.UNITED_HEALTH_GROUP, 125.0).build();
        claimStore.addClaim(uhgTwoMin2, twoMinAgo);
        PayerClaim uhgThreePlus1 =
                getPayerClaimBuilder("U4", PayerId.UNITED_HEALTH_GROUP, 450.0).build();
        claimStore.addClaim(uhgThreePlus1, threeMinAgo);
        PayerClaim uhgThreePlus2 =
                getPayerClaimBuilder("U4a", PayerId.UNITED_HEALTH_GROUP, 175.0).build();
        claimStore.addClaim(uhgThreePlus2, threeMinAgo);

        // Anthem claims in different buckets - multiple entries per bucket
        PayerClaim anthemNow1 = getPayerClaimBuilder("A1", PayerId.ANTHEM, 175.0).build();
        claimStore.addClaim(anthemNow1, now);
        PayerClaim anthemNow2 = getPayerClaimBuilder("A1a", PayerId.ANTHEM, 85.0).build();
        claimStore.addClaim(anthemNow2, thirtySecAgo);
        PayerClaim anthemOneMin1 = getPayerClaimBuilder("A2", PayerId.ANTHEM, 275.0).build();
        claimStore.addClaim(anthemOneMin1, ninetySecAgo);
        PayerClaim anthemOneMin2 = getPayerClaimBuilder("A2a", PayerId.ANTHEM, 125.0).build();
        claimStore.addClaim(anthemOneMin2, ninetySecAgo);
        PayerClaim anthemTwoMin1 = getPayerClaimBuilder("A3", PayerId.ANTHEM, 375.0).build();
        claimStore.addClaim(anthemTwoMin1, twoMinAgo);
        PayerClaim anthemTwoMin2 = getPayerClaimBuilder("A3a", PayerId.ANTHEM, 150.0).build();
        claimStore.addClaim(anthemTwoMin2, twoMinAgo);
        PayerClaim anthemThreePlus1 = getPayerClaimBuilder("A4", PayerId.ANTHEM, 475.0).build();
        claimStore.addClaim(anthemThreePlus1, threeMinAgo);
        PayerClaim anthemThreePlus2 = getPayerClaimBuilder("A4a", PayerId.ANTHEM, 200.0).build();
        claimStore.addClaim(anthemThreePlus2, threeMinAgo);

        GetPayerAccountsReceivableRequest request = GetPayerAccountsReceivableRequest.newBuilder()
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(60)
                        .setEndSecondsAgo(0).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(120)
                        .setEndSecondsAgo(60).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setStartSecondsAgo(180)
                        .setEndSecondsAgo(120).build())
                .addBucket(AccountsReceivableBucket.newBuilder().setEndSecondsAgo(180).build())
                .build();

        GetPayerAccountsReceivableResponse response = executeRequest(claimStore, request);

        assertEquals(3, response.getRowCount(), "Should have 3 rows (one per payer)");

        // Verify Medicare amounts
        AccountsReceivableRow medicareRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.MEDICARE.name())).findFirst()
                .orElseThrow();
        assertEquals(4, medicareRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(CurrencyAmount.from("150.00").toProto(),
                medicareRow.getBucketValue(0).getAmount(),
                "Medicare 0-1min should be 150 (100 + 50)");
        assertEquals(CurrencyAmount.from("275.00").toProto(),
                medicareRow.getBucketValue(1).getAmount(),
                "Medicare 1-2min should be 275 (200 + 75)");
        assertEquals(CurrencyAmount.from("425.00").toProto(),
                medicareRow.getBucketValue(2).getAmount(),
                "Medicare 2-3min should be 425 (300 + 125)");
        assertEquals(CurrencyAmount.from("550.00").toProto(),
                medicareRow.getBucketValue(3).getAmount(),
                "Medicare 3+min should be 550 (400 + 150)");

        // Verify UHG amounts
        AccountsReceivableRow uhgRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.UNITED_HEALTH_GROUP.name()))
                .findFirst().orElseThrow();
        assertEquals(4, uhgRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(CurrencyAmount.from("225.00").toProto(), uhgRow.getBucketValue(0).getAmount(),
                "UHG 0-1min should be 225 (150 + 75)");
        assertEquals(CurrencyAmount.from("350.00").toProto(), uhgRow.getBucketValue(1).getAmount(),
                "UHG 1-2min should be 350 (250 + 100)");
        assertEquals(CurrencyAmount.from("475.00").toProto(), uhgRow.getBucketValue(2).getAmount(),
                "UHG 2-3min should be 475 (350 + 125)");
        assertEquals(CurrencyAmount.from("625.00").toProto(), uhgRow.getBucketValue(3).getAmount(),
                "UHG 3+min should be 625 (450 + 175)");

        // Verify Anthem amounts
        AccountsReceivableRow anthemRow = response.getRowList().stream()
                .filter(row -> row.getPayerId().equals(PayerId.ANTHEM.name())).findFirst()
                .orElseThrow();
        assertEquals(4, anthemRow.getBucketValueCount(), "Should have 4 bucket values");
        assertEquals(CurrencyAmount.from("260.00").toProto(),
                anthemRow.getBucketValue(0).getAmount(), "Anthem 0-1min should be 260 (175 + 85)");
        assertEquals(CurrencyAmount.from("400.00").toProto(),
                anthemRow.getBucketValue(1).getAmount(), "Anthem 1-2min should be 400 (275 + 125)");
        assertEquals(CurrencyAmount.from("525.00").toProto(),
                anthemRow.getBucketValue(2).getAmount(), "Anthem 2-3min should be 525 (375 + 150)");
        assertEquals(CurrencyAmount.from("675.00").toProto(),
                anthemRow.getBucketValue(3).getAmount(), "Anthem 3+min should be 675 (475 + 200)");
    }

    @Test
    public void getAccountsReceivable_anthemFilter_returnsOnlyAnthem() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.ANTHEM, 100.0).build();
        claimStore.addClaim(claim, Instant.now());

        GetPayerAccountsReceivableRequest request = GetPayerAccountsReceivableRequest.newBuilder()
                .addPayerFilter(PayerId.ANTHEM).build();

        GetPayerAccountsReceivableResponse response = executeRequest(claimStore, request);

        assertEquals(1, response.getRowCount(), "Should have 1 row (one per payer)");
        assertEquals(PayerId.ANTHEM.name(), response.getRow(0).getPayerId());
        AccountsReceivableRow anthemRow = response.getRow(0);
        assertEquals(1, anthemRow.getBucketValueCount(), "Should have 1 bucket value");
        assertEquals(CurrencyAmount.from("100.00").toProto(),
                anthemRow.getBucketValue(0).getAmount());
    }

    @Test
    void submitClaim_duplicateHandling() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        SubmitClaimRequest request = SubmitClaimRequest.newBuilder().setClaim(claim).build();

        SubmitClaimResponse response = executeSubmitClaimRequest(claimStore, request);
        assertEquals(SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS, response.getResult());

        response = executeSubmitClaimRequest(claimStore, request);
        assertEquals(SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED, response.getResult());
    }

    @Test
    void getPatientAccountsReceivable() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));

        PayerClaim johnClaim1 =
                getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim1, Instant.now());
        PayerClaim johnClaim2 =
                getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim2, Instant.now());
        PayerClaim janeClaim = getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0)
                .setPatient(Patient.newBuilder().setFirstName("Jane").setLastName("Smith")
                        .setEmail("jane@example.com").setGender(Gender.F).setDob("1985-01-01")
                        .build())
                .build();
        claimStore.addClaim(janeClaim, Instant.now());

        Remittance remittanceResponse = Remittance.newBuilder().setClaimId("C1")
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("C1", remittanceResponse, Instant.now());

        PatientStore patientStore = new PatientStore();
        for (PayerClaim claim : claimStore.getClaims().values()) {
            patientStore.addPatient(claim.getPatient());
        }

        GetPatientAccountsReceivableResponse response =
                executePatientAccountsReceivableRequest(patientStore, claimStore);

        assertEquals(2, response.getRowCount(), "Should have 2 rows (one per patient)");
        PatientAccountsReceivableRow johnRow =
                response.getRowList().stream()
                        .filter(row -> row.getPatient().getFirstName().equals("John")
                                && row.getPatient().getLastName().equals("Doe"))
                        .findFirst().orElseThrow();
        assertEquals(CurrencyAmount.from("10.00").toProto(),
                johnRow.getBalance().getOutstandingCopay(), "John's copay should be 10.0");
        assertEquals(CurrencyAmount.from("5.00").toProto(),
                johnRow.getBalance().getOutstandingCoinsurance(),
                "John's coinsurance should be 5.0");
        assertEquals(CurrencyAmount.from("5.00").toProto(),
                johnRow.getBalance().getOutstandingDeductible(), "John's deductible should be 5.0");
        PatientAccountsReceivableRow janeRow = response.getRowList().stream()
                .filter(row -> row.getPatient().getFirstName().equals("Jane")
                        && row.getPatient().getLastName().equals("Smith"))
                .findFirst().orElseThrow();
        assertEquals(CurrencyAmount.from("0.00").toProto(),
                janeRow.getBalance().getOutstandingCopay(), "Jane's copay should be 0.0");
        assertEquals(CurrencyAmount.from("0.00").toProto(),
                janeRow.getBalance().getOutstandingCoinsurance(),
                "Jane's coinsurance should be 0.0");
        assertEquals(CurrencyAmount.from("0.00").toProto(),
                janeRow.getBalance().getOutstandingDeductible(), "Jane's deductible should be 0.0");
    }

    @Test
    void getPatientAccountsReceivable_allResponsesReceived() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));

        PayerClaim johnClaim1 =
                getPayerClaimBuilder("C1", PayerId.MEDICARE, 100.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim1, Instant.now());
        PayerClaim johnClaim2 =
                getPayerClaimBuilder("C2", PayerId.MEDICARE, 200.0).setPatient(Patient.newBuilder()
                        .setFirstName("John").setLastName("Doe").setEmail("john@example.com")
                        .setGender(Gender.M).setDob("1980-01-01").build()).build();
        claimStore.addClaim(johnClaim2, Instant.now());
        PayerClaim janeClaim = getPayerClaimBuilder("C3", PayerId.UNITED_HEALTH_GROUP, 150.0)
                .setPatient(Patient.newBuilder().setFirstName("Jane").setLastName("Smith")
                        .setEmail("jane@example.com").setGender(Gender.F).setDob("1985-01-01")
                        .build())
                .build();
        claimStore.addClaim(janeClaim, Instant.now());

        Remittance remittanceResponse = Remittance.newBuilder().setClaimId("C1")
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("C1", remittanceResponse, Instant.now());

        remittanceResponse = Remittance.newBuilder().setClaimId("C2")
                .setPayerPaidAmount(CurrencyAmount.from("160.00").toProto())
                .setCopayAmount(CurrencyAmount.from("20.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("10.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("10.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("C2", remittanceResponse, Instant.now());

        remittanceResponse = Remittance.newBuilder().setClaimId("C3")
                .setPayerPaidAmount(CurrencyAmount.from("120.00").toProto())
                .setCopayAmount(CurrencyAmount.from("15.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("10.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("C3", remittanceResponse, Instant.now());

        PatientStore patientStore = new PatientStore();
        for (PayerClaim claim : claimStore.getClaims().values()) {
            patientStore.addPatient(claim.getPatient());
        }

        GetPatientAccountsReceivableResponse response =
                executePatientAccountsReceivableRequest(patientStore, claimStore);

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
        assertEquals(CurrencyAmount.from("30.00").toProto(),
                johnRow.getBalance().getOutstandingCopay(),
                "John's copay should be 30.0 (10.0 + 20.0)");
        assertEquals(CurrencyAmount.from("15.00").toProto(),
                johnRow.getBalance().getOutstandingCoinsurance(),
                "John's coinsurance should be 15.0 (5.0 + 10.0)");
        assertEquals(CurrencyAmount.from("15.00").toProto(),
                johnRow.getBalance().getOutstandingDeductible(),
                "John's deductible should be 15.0 (5.0 + 10.0)");

        // For Jane:
        // - C3: 15.0 copay, 10.0 coinsurance, 5.0 deductible
        PatientAccountsReceivableRow janeRow = response.getRowList().stream()
                .filter(row -> row.getPatient().getFirstName().equals("Jane")
                        && row.getPatient().getLastName().equals("Smith"))
                .findFirst().orElseThrow();
        assertEquals(CurrencyAmount.from("15.00").toProto(),
                janeRow.getBalance().getOutstandingCopay(), "Jane's copay should be 15.0");
        assertEquals(CurrencyAmount.from("10.00").toProto(),
                janeRow.getBalance().getOutstandingCoinsurance(),
                "Jane's coinsurance should be 10.0");
        assertEquals(CurrencyAmount.from("5.00").toProto(),
                janeRow.getBalance().getOutstandingDeductible(), "Jane's deductible should be 5.0");
    }

    @Test
    void submitPatientPayment_success() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim, Instant.now());

        Remittance remittanceResponse = Remittance.newBuilder().setClaimId("TEST1")
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("TEST1", remittanceResponse, Instant.now());

        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("TEST1").setAmount(CurrencyAmount.from("15.00").toProto()).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(
                SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_PAYMENT_APPLIED_BALANCING_OUTSTANDING,
                response.getResult());
    }

    @Test
    void submitPatientPayment_claimNotFound() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        SubmitPatientPaymentRequest request =
                SubmitPatientPaymentRequest.newBuilder().setClaimId("NONEXISTENT")
                        .setAmount(CurrencyAmount.from("15.00").toProto()).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_ERROR,
                response.getResult());
    }

    @Test
    void submitPatientPayment_noOutstandingBalance() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim, Instant.now());

        // No remittance response means no patient responsibility
        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("TEST1").setAmount(CurrencyAmount.from("15.00").toProto()).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE,
                response.getResult());
    }

    @Test
    void submitPatientPayment_amountExceedsBalance() throws Exception {
        ClaimStore claimStore = new ClaimStore(tempDir.resolve("never.json"));
        PayerClaim claim = getPayerClaimBuilder("TEST1", PayerId.MEDICARE, 100.0).build();
        claimStore.addClaim(claim, Instant.now());

        Remittance remittanceResponse = Remittance.newBuilder().setClaimId("TEST1")
                .setPayerPaidAmount(CurrencyAmount.from("80.00").toProto())
                .setCopayAmount(CurrencyAmount.from("10.00").toProto())
                .setCoinsuranceAmount(CurrencyAmount.from("5.00").toProto())
                .setDeductibleAmount(CurrencyAmount.from("5.00").toProto())
                .setNotAllowedAmount(CurrencyAmount.from("0.00").toProto()).build();
        claimStore.addResponse("TEST1", remittanceResponse, Instant.now());

        // Exceeds 10 + 5 + 5 = 20
        SubmitPatientPaymentRequest request = SubmitPatientPaymentRequest.newBuilder()
                .setClaimId("TEST1").setAmount(CurrencyAmount.from("25.00").toProto()).build();

        SubmitPatientPaymentResponse response =
                executeSubmitPatientPaymentRequest(claimStore, request);
        assertEquals(
                SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_AMOUNT_EXCEEDS_OUTSTANDING_BALANCE,
                response.getResult());
    }

    private GetPayerAccountsReceivableResponse executeRequest(ClaimStore claimStore,
            GetPayerAccountsReceivableRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetPayerAccountsReceivableResponse[] responseHolder =
                new GetPayerAccountsReceivableResponse[1];
        PatientStore patientStore = new PatientStore();
        BillingService billingService = new BillingService(new PayerPaymentHelper(claimStore),
                new PatientPaymentHelper(claimStore, patientStore), patientStore, claimStore,
                clearingHouseClient);
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

    private SubmitClaimResponse executeSubmitClaimRequest(ClaimStore claimStore,
            SubmitClaimRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SubmitClaimResponse[] responseHolder = new SubmitClaimResponse[1];
        PatientStore patientStore = new PatientStore();
        BillingService billingService = new BillingService(new PayerPaymentHelper(claimStore),
                new PatientPaymentHelper(claimStore, patientStore), patientStore, claimStore,
                clearingHouseClient);
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
            PatientStore patientStore, ClaimStore claimStore) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetPatientAccountsReceivableResponse[] responseHolder =
                new GetPatientAccountsReceivableResponse[1];
        BillingService billingService = new BillingService(new PayerPaymentHelper(claimStore),
                new PatientPaymentHelper(claimStore, patientStore), patientStore, claimStore,
                clearingHouseClient);
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
        PatientStore patientStore = new PatientStore();
        BillingService billingService = new BillingService(new PayerPaymentHelper(claimStore),
                new PatientPaymentHelper(claimStore, patientStore), patientStore, claimStore,
                clearingHouseClient);
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

    private static PayerClaim.Builder getPayerClaimBuilder(String claimId, PayerId payerId,
            double amount) {
        BigDecimal amountBigDecimal = BigDecimal.valueOf(amount);
        return PayerClaim.newBuilder().setClaimId(claimId)
                .setInsurance(Insurance.newBuilder().setPayerId(payerId).setPatientMemberId("PM123")
                        .build())
                .addServiceLines(
                        ServiceLine.newBuilder().setServiceLineId("SL1").setProcedureCode("99213")
                                .setCharge(CurrencyAmount.from(amountBigDecimal).toProto())
                                .setDoNotBill(false).build());
    }

    private static class SuccessClearingHouseClient implements ClearingHouseClient {
        @Override
        public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
            return ProcessClaimResponse.newBuilder().setSuccess(true).build();
        }
    }
}

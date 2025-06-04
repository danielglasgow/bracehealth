package com.bracehealth.billing;

import static org.junit.jupiter.api.Assertions.*;

import com.bracehealth.shared.*;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableBucketValue;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableRow;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;

class PayerPaymentHelperTest {

    @TempDir
    Path tempDir;

    private ClaimStore claimStore;
    private PayerPaymentHelper payerPaymentHelper;

    @BeforeEach
    void setUp() {
        claimStore = new ClaimStore(tempDir.resolve("claims.json"));
        payerPaymentHelper = new PayerPaymentHelper(claimStore);
    }

    @Test
    void getPayerAccountsReceivable_withMultipleBuckets_returnsCorrectAmounts() {
        // Create test patient
        Patient patient = createTestPatient("John", "Doe");

        // Create and seed claims with different timestamps
        Instant now = Instant.now();

        // Medicare claims in different buckets
        PayerClaim medicareNow = createTestClaim("M1", PayerId.MEDICARE, 100.0, patient);
        claimStore.addClaim(medicareNow, now.minusSeconds(5)); // 0-10s bucket

        PayerClaim medicareRecent = createTestClaim("M2", PayerId.MEDICARE, 200.0, patient);
        claimStore.addClaim(medicareRecent, now.minusSeconds(15)); // 10-20s bucket

        PayerClaim medicareOlder = createTestClaim("M3", PayerId.MEDICARE, 300.0, patient);
        claimStore.addClaim(medicareOlder, now.minusSeconds(25)); // 20-30s bucket

        PayerClaim medicareOldest = createTestClaim("M4", PayerId.MEDICARE, 400.0, patient);
        claimStore.addClaim(medicareOldest, now.minusSeconds(35)); // 30s+ bucket

        // UHG claims in different buckets
        PayerClaim uhgNow = createTestClaim("U1", PayerId.UNITED_HEALTH_GROUP, 150.0, patient);
        claimStore.addClaim(uhgNow, now.minusSeconds(5)); // 0-10s bucket

        PayerClaim uhgRecent = createTestClaim("U2", PayerId.UNITED_HEALTH_GROUP, 250.0, patient);
        claimStore.addClaim(uhgRecent, now.minusSeconds(15)); // 10-20s bucket

        PayerClaim uhgOlder = createTestClaim("U3", PayerId.UNITED_HEALTH_GROUP, 350.0, patient);
        claimStore.addClaim(uhgOlder, now.minusSeconds(25)); // 20-30s bucket

        PayerClaim uhgOldest = createTestClaim("U4", PayerId.UNITED_HEALTH_GROUP, 450.0, patient);
        claimStore.addClaim(uhgOldest, now.minusSeconds(35)); // 30s+ bucket

        // Define buckets
        List<AccountsReceivableBucket> buckets = List.of(
                AccountsReceivableBucket.newBuilder().setStartSecondsAgo(10).setEndSecondsAgo(0)
                        .build(),
                AccountsReceivableBucket.newBuilder().setStartSecondsAgo(20).setEndSecondsAgo(10)
                        .build(),
                AccountsReceivableBucket.newBuilder().setStartSecondsAgo(30).setEndSecondsAgo(20)
                        .build(),
                AccountsReceivableBucket.newBuilder().setStartSecondsAgo(0).setEndSecondsAgo(30)
                        .build());

        // Get accounts receivable
        GetPayerAccountsReceivableResponse response = payerPaymentHelper.getPayerAccountsReceivable(
                ImmutableList.of(PayerId.MEDICARE, PayerId.UNITED_HEALTH_GROUP),
                ImmutableList.copyOf(buckets));

        // Verify response
        assertEquals(2, response.getRowCount(), "Should have 2 rows (one per payer)");

        // Verify Medicare amounts
        AccountsReceivableRow medicareRow = getPayerRow(response, PayerId.MEDICARE);
        assertEquals(4, medicareRow.getBucketValueCount(), "Should have 4 bucket values");

        var medicare0to10 = getBucketValue(medicareRow, 10, 0);
        assertEquals(100, medicare0to10.getAmount().getWholeAmount());
        assertEquals(0, medicare0to10.getAmount().getDecimalAmount());

        var medicare10to20 = getBucketValue(medicareRow, 20, 10);
        assertEquals(200, medicare10to20.getAmount().getWholeAmount());
        assertEquals(0, medicare10to20.getAmount().getDecimalAmount());

        var medicare20to30 = getBucketValue(medicareRow, 30, 20);
        assertEquals(300, medicare20to30.getAmount().getWholeAmount());
        assertEquals(0, medicare20to30.getAmount().getDecimalAmount());

        var medicare30plus = getBucketValue(medicareRow, 0, 30);
        assertEquals(400, medicare30plus.getAmount().getWholeAmount());
        assertEquals(0, medicare30plus.getAmount().getDecimalAmount());

        // Verify UHG amounts
        AccountsReceivableRow uhgRow = getPayerRow(response, PayerId.UNITED_HEALTH_GROUP);
        assertEquals(4, uhgRow.getBucketValueCount(), "Should have 4 bucket values");

        var uhg0to10 = getBucketValue(uhgRow, 10, 0);
        assertEquals(150, uhg0to10.getAmount().getWholeAmount());
        assertEquals(0, uhg0to10.getAmount().getDecimalAmount());

        var uhg10to20 = getBucketValue(uhgRow, 20, 10);
        assertEquals(250, uhg10to20.getAmount().getWholeAmount());
        assertEquals(0, uhg10to20.getAmount().getDecimalAmount());

        var uhg20to30 = getBucketValue(uhgRow, 30, 20);
        assertEquals(350, uhg20to30.getAmount().getWholeAmount());
        assertEquals(0, uhg20to30.getAmount().getDecimalAmount());

        var uhg30plus = getBucketValue(uhgRow, 0, 30);
        assertEquals(450, uhg30plus.getAmount().getWholeAmount());
        assertEquals(0, uhg30plus.getAmount().getDecimalAmount());
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

    private AccountsReceivableRow getPayerRow(GetPayerAccountsReceivableResponse response,
            PayerId payerId) {
        return response.getRowList().stream().filter(row -> row.getPayerId().equals(payerId.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row found for payer " + payerId));
    }

    private AccountsReceivableBucketValue getBucketValue(AccountsReceivableRow row,
            int startSecondsAgo, int endSecondsAgo) {
        return row.getBucketValueList().stream()
                .filter(bv -> bv.getBucket().getStartSecondsAgo() == startSecondsAgo
                        && bv.getBucket().getEndSecondsAgo() == endSecondsAgo)
                .findFirst().orElseThrow(() -> new AssertionError("No bucket found for range "
                        + startSecondsAgo + "-" + endSecondsAgo + " seconds ago"));
    }
}

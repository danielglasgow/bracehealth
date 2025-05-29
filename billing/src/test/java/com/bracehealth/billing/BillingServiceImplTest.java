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
                        .newBuilder().setStartMinutesAgo(120).setEndMinutesAgo(0).build()).build();

        GetAccountsReceivableResponse response = executeRequest(claimStore, request);
        System.out.println(response);

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


    private static GetAccountsReceivableResponse executeRequest(ClaimStore claimStore,
            GetAccountsReceivableRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        GetAccountsReceivableResponse[] responseHolder = new GetAccountsReceivableResponse[1];

        BillingServiceGrpc.BillingServiceImplBase billingService =
                new BillingServiceImpl(claimStore);

        billingService.getAccountsReceivable(request,
                new StreamObserver<GetAccountsReceivableResponse>() {
                    @Override
                    public void onNext(GetAccountsReceivableResponse response) {
                        responseHolder[0] = response;
                    }

                    @Override
                    public void onError(Throwable t) {
                        fail("Request failed", t);
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request timed out");
        assertNotNull(responseHolder[0], "Response should not be null");
        return responseHolder[0];
    }

    private static ClaimStore.Claim createPendingClaim(PayerClaim claim, Instant submittedAt) {
        return new ClaimStore.Claim(claim, submittedAt, ClaimStore.ClaimStatus.PENDING,
                Optional.empty());
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
}

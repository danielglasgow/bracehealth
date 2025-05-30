package com.bracehealth.billing;

import com.bracehealth.shared.*;
import com.google.common.collect.ImmutableList;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@GrpcService
public class BillingServiceImpl extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingServiceImpl.class);
    private final ClaimStore claimStore;

    @Autowired
    public BillingServiceImpl(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    @Override
    public void submitClaim(SubmitClaimRequest request,
            StreamObserver<SubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim submission for claim ID: {}", claim.getClaimId());
            if (claimStore.containsClaim(claim.getClaimId())) {
                logger.error("Claim with ID {} already exists", claim.getClaimId());
                SubmitClaimResponse response =
                        SubmitClaimResponse.newBuilder().setSuccess(false).build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            claimStore.addClaim(claim);

            SubmitClaimResponse response =
                    SubmitClaimResponse.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing claim submission", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void receiveRemittance(ReceiveRemittanceRequest request,
            StreamObserver<ReceiveRemittanceResponse> responseObserver) {
        try {
            RemittanceResponse remittance = request.getRemittance();
            logger.info("Received remittance for claim ID: {}, paid amount: {}",
                    remittance.getClaimId(), remittance.getPayerPaidAmount());

            claimStore.addResponse(remittance.getClaimId(), remittance);

            ReceiveRemittanceResponse response =
                    ReceiveRemittanceResponse.newBuilder().setSuccess(true).build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing remittance", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getAccountsReceivable(GetAccountsReceivableRequest request,
            StreamObserver<GetAccountsReceivableResponse> responseObserver) {
        // TODO: Add verification that buckets are non-overlapping
        try {
            logger.info("Received accounts receivable request with {} buckets",
                    request.getBucketCount());

            // TODO: This should really be cached, similarly, we should not look at completed claims
            Map<PayerId, List<ClaimStore.Claim>> claimsByPayer =
                    claimStore.getPendingClaims().values().stream().collect(Collectors
                            .groupingBy(claim -> claim.claim().getInsurance().getPayerId()));

            // Create response builder
            GetAccountsReceivableResponse.Builder responseBuilder =
                    GetAccountsReceivableResponse.newBuilder();

            ImmutableList<PayerId> targetPayer = request.getPayerFilterList().size() == 0
                    ? ImmutableList.copyOf(claimsByPayer.keySet())
                    : ImmutableList.copyOf(request.getPayerFilterList());

            for (PayerId payerId : targetPayer) {
                responseBuilder.addRow(
                        createRow(payerId, claimsByPayer.get(payerId), request.getBucketList()));

            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing accounts receivable request", e);
            responseObserver.onError(e);
        }
    }

    private AccountsReceivableRow createRow(PayerId payerId, List<ClaimStore.Claim> claims,
            List<AccountsReceivableBucket> buckets) {
        return AccountsReceivableRow.newBuilder().setPayerId(payerId.name())
                .setPayerName(payerId.name())
                .addAllBucketValue(buckets.stream()
                        .map(bucket -> AccountsReceivableBucketValue.newBuilder().setBucket(bucket)
                                .setAmount(calculateBucketAmount(claims, bucket)).build())
                        .collect(Collectors.toList()))
                .build();
    }

    private double calculateBucketAmount(List<ClaimStore.Claim> claims,
            AccountsReceivableBucket bucket) {
        Instant now = Instant.now();
        Instant startTime =
                bucket.getStartSecondsAgo() > 0 ? now.minusSeconds(bucket.getStartSecondsAgo())
                        : Instant.EPOCH;
        Instant endTime =
                bucket.getEndSecondsAgo() > 0 ? now.minusSeconds(bucket.getEndSecondsAgo()) : now;

        return claims.stream()
                .filter(claim -> claim.submittedAt().isAfter(startTime)
                        && claim.submittedAt().isBefore(endTime))
                .mapToDouble(claim -> claim.claim().getServiceLinesList().stream()
                        .filter(sl -> !sl.getDoNotBill())
                        .mapToDouble(sl -> sl.getUnitChargeAmount() * sl.getUnits()).sum())
                .sum();
    }
}

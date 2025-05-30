package com.bracehealth.billing;

import com.bracehealth.shared.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
    private final ClearingHouseClient clearingHouseClient;

    @Autowired
    public BillingServiceImpl(ClaimStore claimStore, ClearingHouseClient clearingHouseClient) {
        this.claimStore = claimStore;
        this.clearingHouseClient = clearingHouseClient;
    }

    @Override
    public void submitClaim(SubmitClaimRequest request,
            StreamObserver<SubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim submission for claim ID: {}", claim.getClaimId());
            if (claimStore.containsClaim(claim.getClaimId())) {
                logger.error("Claim with ID {} already exists", claim.getClaimId());
                responseObserver.onNext(
                        createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED));
                responseObserver.onCompleted();
                return;
            }


            try {
                ClearingHouseSubmitClaimResponse clearingHouseResponse =
                        clearingHouseClient.submitClaim(request);
                if (!clearingHouseResponse.getSuccess()) {
                    logger.error("Failed to submit claim {} to clearinghouse", claim.getClaimId());
                    responseObserver
                            .onNext(createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE));
                    responseObserver.onCompleted();
                    return;

                }
            } catch (Exception e) {
                logger.error("Error submitting claim {} to clearinghouse", claim.getClaimId(), e);
                responseObserver
                        .onNext(createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE));
                responseObserver.onCompleted();
                return;

            }
            claimStore.addClaim(claim);
            responseObserver.onNext(createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS));
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing claim submission", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void submitRemittance(SubmitRemittanceRequest request,
            StreamObserver<SubmitRemittanceResponse> responseObserver) {
        try {
            RemittanceResponse remittance = request.getRemittance();
            logger.info("Received remittance for claim ID: {}", remittance.getClaimId());
            claimStore.addResponse(remittance.getClaimId(), remittance);
            responseObserver.onNext(SubmitRemittanceResponse.newBuilder().setSuccess(true).build());
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

            // TODO: This should really be cached (and an immutable data structure)
            Map<PayerId, List<ClaimStore.Claim>> claimsByPayer =
                    claimStore.getPendingClaims().values().stream().collect(Collectors
                            .groupingBy(claim -> claim.claim().getInsurance().getPayerId()));

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

    @Override
    public void getPatientAccountsReceivable(GetPatientAccountsReceivableRequest request,
            StreamObserver<GetPatientAccountsReceivableResponse> responseObserver) {
        try {
            logger.info("Received patient accounts receivable request");

            ImmutableMap<Patient, ImmutableList<ClaimStore.Claim>> claimsByPatient =
                    claimStore.getClaimsByPatient();

            GetPatientAccountsReceivableResponse.Builder responseBuilder =
                    GetPatientAccountsReceivableResponse.newBuilder();

            for (Map.Entry<Patient, ImmutableList<ClaimStore.Claim>> entry : claimsByPatient
                    .entrySet()) {
                Patient patient = entry.getKey();
                ImmutableList<ClaimStore.Claim> patientClaims = entry.getValue();
                double outstandingCopay = 0.0;
                double outstandingCoinsurance = 0.0;
                double outstandingDeductible = 0.0;
                for (ClaimStore.Claim claim : patientClaims) {
                    if (claim.status() == ClaimStore.ClaimStatus.RESPONSE_RECEIVED) {
                        Optional<RemittanceResponse> remittance = claim.clearingHouseResponse()
                                .map(ClaimStore.ClearingHouseResponse::remittanceResponse);
                        outstandingCopay +=
                                remittance.map(RemittanceResponse::getCopayAmount).orElse(0.0);
                        outstandingCoinsurance += remittance
                                .map(RemittanceResponse::getCoinsuranceAmount).orElse(0.0);
                        outstandingDeductible +=
                                remittance.map(RemittanceResponse::getDeductibleAmount).orElse(0.0);
                    }
                }
                responseBuilder.addRow(PatientAccountsReceivableRow.newBuilder().setPatient(patient)
                        .setOutstandingCopay(outstandingCopay)
                        .setOutstandingCoinsurance(outstandingCoinsurance)
                        .setOutstandingDeductible(outstandingDeductible).build());
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing patient accounts receivable request", e);
            responseObserver.onError(e);
        }
    }

    private static SubmitClaimResponse createResponse(SubmitClaimResult result) {
        return SubmitClaimResponse.newBuilder().setResult(result).build();
    }


}

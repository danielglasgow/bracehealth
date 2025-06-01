package com.bracehealth.billing;

import com.bracehealth.billing.ClaimStore.ClaimProcessingInfo;
import com.bracehealth.shared.AccountsReceivableBucket;
import com.bracehealth.shared.GetPayerAccountsReceivableRequest;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableRow;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse.AccountsReceivableBucketValue;
import com.bracehealth.shared.GetPatientAccountsReceivableRequest;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse.PatientAccountsReceivableRow;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.NotifyRemittanceResponse.NotifyRemittanceResult;
import com.bracehealth.shared.PatientBalance;
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
import com.bracehealth.shared.BillingServiceGrpc;
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
public class BillingService extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final ClaimStore claimStore;
    private final ClearingHouseClient clearingHouseClient;

    @Autowired
    public BillingService(ClaimStore claimStore, ClearingHouseClient clearingHouseClient) {
        this.claimStore = claimStore;
        this.clearingHouseClient = clearingHouseClient;
    }

    @Override
    public void submitClaim(SubmitClaimRequest request,
            StreamObserver<SubmitClaimResponse> observer) {
        SubmitClaimResponse response = submitClaimInternal(request.getClaim());
        observer.onNext(response);
        observer.onCompleted();
    }

    private SubmitClaimResponse submitClaimInternal(PayerClaim claim) {
        logger.info("Received claim submission for claim ID: {}", claim.getClaimId());
        if (claimStore.containsClaim(claim.getClaimId())) {
            logger.error("Claim with ID {} already exists", claim.getClaimId());
            return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED);
        }

        try {
            ProcessClaimRequest request = ProcessClaimRequest.newBuilder().setClaim(claim).build();
            ProcessClaimResponse response = clearingHouseClient.processClaim(request);
            if (!response.getSuccess()) {
                logger.error("Failed to submit claim {} to clearinghouse", claim.getClaimId());
                return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE);

            }
        } catch (Exception e) {
            logger.error("Error submitting claim {} to clearinghouse", claim.getClaimId(), e);
            return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_FAILURE);

        }
        claimStore.addClaim(claim, Instant.now());
        return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS);
    }

    @Override
    public void notifyRemittance(NotifyRemittanceRequest request,
            StreamObserver<NotifyRemittanceResponse> observer) {
        Remittance remittance = request.getRemittance();
        logger.info("Received remittance for claim ID: {}", remittance.getClaimId());
        claimStore.addResponse(remittance.getClaimId(), remittance, Instant.now());
        observer.onNext(NotifyRemittanceResponse.newBuilder()
                .setResult(NotifyRemittanceResult.NOTIFY_REMITTANCE_RESULT_SUCCESS).build());
        observer.onCompleted();
    }

    @Override
    public void getPayerAccountsReceivable(GetPayerAccountsReceivableRequest request,
            StreamObserver<GetPayerAccountsReceivableResponse> observer) {
        // TODO: Add verification that buckets are non-overlapping
        logger.info("Received accounts receivable request with {} buckets",
                request.getBucketCount());

        ImmutableMap<PayerId, ImmutableList<PayerClaim>> claimsByPayer =
                claimStore.getClaimsByPayer();

        GetPayerAccountsReceivableResponse.Builder responseBuilder =
                GetPayerAccountsReceivableResponse.newBuilder();
        ImmutableList<PayerId> targetPayer = request.getPayerFilterList().size() == 0
                ? ImmutableList.copyOf(claimsByPayer.keySet())
                : ImmutableList.copyOf(request.getPayerFilterList());
        for (PayerId payerId : targetPayer) {
            responseBuilder.addRow(
                    createRow(payerId, claimsByPayer.get(payerId), request.getBucketList()));

        }
        observer.onNext(responseBuilder.build());
        observer.onCompleted();
    }

    private AccountsReceivableRow createRow(PayerId payerId, List<PayerClaim> claims,
            List<AccountsReceivableBucket> buckets) {
        return AccountsReceivableRow.newBuilder().setPayerId(payerId.name())
                .setPayerName(payerId.name())
                .addAllBucketValue(buckets.stream()
                        .map(bucket -> AccountsReceivableBucketValue.newBuilder().setBucket(bucket)
                                .setAmount(calculateBucketAmount(claims, bucket)).build())
                        .collect(Collectors.toList()))
                .build();
    }

    private double calculateBucketAmount(List<PayerClaim> claims, AccountsReceivableBucket bucket) {
        Instant now = Instant.now();
        Instant startTime =
                bucket.getStartSecondsAgo() > 0 ? now.minusSeconds(bucket.getStartSecondsAgo())
                        : Instant.EPOCH;
        Instant endTime =
                bucket.getEndSecondsAgo() > 0 ? now.minusSeconds(bucket.getEndSecondsAgo()) : now;

        return claims.stream().filter(claim -> claimStore.getProcessingInfo(claim.getClaimId())
                .submittedAt().isAfter(startTime)
                && claimStore.getProcessingInfo(claim.getClaimId()).submittedAt().isBefore(endTime))
                .mapToDouble(claim -> claim.getServiceLinesList().stream()
                        .filter(sl -> !sl.getDoNotBill())
                        .mapToDouble(sl -> sl.getUnitChargeAmount() * sl.getUnits()).sum())
                .sum();
    }

    @Override
    public void getPatientAccountsReceivable(GetPatientAccountsReceivableRequest request,
            StreamObserver<GetPatientAccountsReceivableResponse> observer) {
        try {
            logger.info("Received patient accounts receivable request");

            ImmutableMap<Patient, ImmutableList<PayerClaim>> claimsByPatient =
                    claimStore.getClaimsByPatient();

            GetPatientAccountsReceivableResponse.Builder responseBuilder =
                    GetPatientAccountsReceivableResponse.newBuilder();

            for (Map.Entry<Patient, ImmutableList<PayerClaim>> entry : claimsByPatient.entrySet()) {
                Patient patient = entry.getKey();
                ImmutableList<PayerClaim> patientClaims = entry.getValue();
                double outstandingCopay = 0.0;
                double outstandingCoinsurance = 0.0;
                double outstandingDeductible = 0.0;
                for (PayerClaim claim : patientClaims) {
                    Remittance remittance = claimStore.getRemittance(claim.getClaimId());
                    if (remittance != null) {
                        outstandingCopay += remittance.getCopayAmount();
                        outstandingCoinsurance += remittance.getCoinsuranceAmount();
                        outstandingDeductible += remittance.getDeductibleAmount();
                        // VERY HACKY update of patient payments
                        double patientPayment = claimStore.getPatientPayment(claim.getClaimId());
                        if (patientPayment > outstandingCopay) {
                            patientPayment -= outstandingCopay;
                            outstandingCopay = 0.0;
                        } else {
                            outstandingCopay -= patientPayment;
                            patientPayment = 0.0;
                        }
                        if (patientPayment > outstandingCoinsurance) {
                            patientPayment -= outstandingCoinsurance;
                            outstandingCoinsurance = 0.0;
                        } else {
                            outstandingCoinsurance -= patientPayment;
                            patientPayment = 0.0;
                        }
                        if (patientPayment > outstandingDeductible) {
                            patientPayment -= outstandingDeductible;
                            outstandingDeductible = 0.0;
                        } else {
                            outstandingDeductible -= patientPayment;
                            patientPayment = 0.0;
                        }
                    }
                }
                PatientBalance balance =
                        PatientBalance.newBuilder().setOutstandingCopay(outstandingCopay)
                                .setOutstandingCoinsurance(outstandingCoinsurance)
                                .setOutstandingDeductible(outstandingDeductible).build();
                responseBuilder
                        .addRow(PatientAccountsReceivableRow.newBuilder().setPatient(patient)
                                .setBalance(balance).addAllClaimId(patientClaims.stream()
                                        .map(PayerClaim::getClaimId).collect(Collectors.toList()))
                                .build());
            }

            observer.onNext(responseBuilder.build());
            observer.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing patient accounts receivable request", e);
            observer.onError(e);
        }
    }

    @Override
    public void submitPatientPayment(SubmitPatientPaymentRequest request,
            StreamObserver<SubmitPatientPaymentResponse> observer) {
        try {
            String claimId = request.getClaimId();
            double amount = request.getAmount();
            logger.info("Received patient payment of {} for claim ID: {}", amount, claimId);

            if (!claimStore.containsClaim(claimId)) {
                logger.error("Claim with ID {} not found", claimId);
                observer.onNext(SubmitPatientPaymentResponse.newBuilder()
                        .setResult(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FAILURE)
                        .build());
                observer.onCompleted();
                return;
            }

            double outstandingBalance = getOutstandingPatientBalance(claimId);
            if (outstandingBalance <= 0) {
                logger.error("No outstanding balance for claim ID {}", claimId);
                observer.onNext(SubmitPatientPaymentResponse.newBuilder().setResult(
                        SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE)
                        .build());
                observer.onCompleted();
                return;
            }

            if (amount > outstandingBalance) {
                logger.error("Payment amount {} exceeds outstanding balance {} for claim ID {}",
                        amount, outstandingBalance, claimId);
                observer.onNext(SubmitPatientPaymentResponse.newBuilder()
                        .setResult(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FAILURE)
                        .build());
                observer.onCompleted();
                return;
            }

            claimStore.addPatientPayment(claimId, amount);
            observer.onNext(SubmitPatientPaymentResponse.newBuilder()
                    .setResult(SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_SUCCESS)
                    .build());
            observer.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing patient payment", e);
            observer.onError(e);
        }
    }

    private static SubmitClaimResponse createResponse(SubmitClaimResult result) {
        return SubmitClaimResponse.newBuilder().setResult(result).build();
    }

    private double getOutstandingPatientBalance(String claimId) {
        ClaimProcessingInfo processingInfo = claimStore.getProcessingInfo(claimId);
        if (processingInfo.responseReceivedAt().isEmpty()) {
            return 0.0;
        }
        Remittance remittance = claimStore.getRemittance(claimId);
        if (remittance == null) {
            throw new IllegalStateException("Remittance not found for claim ID: " + claimId);
        }
        double totalPatientResponsibility = remittance.getCopayAmount()
                + remittance.getCoinsuranceAmount() + remittance.getDeductibleAmount();
        double patientPayment = claimStore.getPatientPayment(claimId);
        return totalPatientResponsibility - patientPayment;
    }
}

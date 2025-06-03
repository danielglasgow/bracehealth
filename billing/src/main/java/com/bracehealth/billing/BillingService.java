package com.bracehealth.billing;

import com.bracehealth.shared.GetPayerAccountsReceivableRequest;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientAccountsReceivableRequest;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientClaimsRequest;
import com.bracehealth.shared.GetPatientClaimsResponse;
import com.bracehealth.shared.GetPatientClaimsResponse.PatientClaimRow;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.NotifyRemittanceResponse.NotifyRemittanceResult;
import com.bracehealth.shared.Patient;
import com.bracehealth.shared.PatientBalance;
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
import com.bracehealth.billing.ClaimStore.ClaimProcessingInfo;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import com.bracehealth.billing.PatientStore.PatientId;
import com.bracehealth.shared.AccountsReceivableBucket;
import com.bracehealth.shared.BillingServiceGrpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

@GrpcService
public class BillingService extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final PayerPaymentHelper payerPayments;
    private final PatientPaymentHelper patientPayments;
    private final PatientStore patientStore;
    private final ClaimStore claimStore;
    private final ClearingHouseClient clearingHouseClient;

    @Autowired
    public BillingService(PayerPaymentHelper payerPayments, PatientPaymentHelper patientPayments,
            PatientStore patientStore, ClaimStore claimStore,
            ClearingHouseClient clearingHouseClient) {
        this.payerPayments = payerPayments;
        this.patientStore = patientStore;
        this.patientPayments = patientPayments;
        this.claimStore = claimStore;
        this.clearingHouseClient = clearingHouseClient;
    }

    @Override
    public void submitClaim(SubmitClaimRequest request,
            StreamObserver<SubmitClaimResponse> observer) {
        SubmitClaimResponse response =
                submitClaimInternal(request.getClaim(), request.getSendTimeSeconds());
        observer.onNext(response);
        observer.onCompleted();
    }

    private SubmitClaimResponse submitClaimInternal(PayerClaim claim, long sendTimeSeconds) {
        logger.info("Received claim submission for claim ID: {}", claim.getClaimId());
        if (claimStore.containsClaim(claim.getClaimId())) {
            logger.error("Claim with ID {} already exists", claim.getClaimId());
            return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_ALREADY_SUBMITTED);
        }
        Patient patient = claim.getPatient();
        if (!patientStore.addPatient(patient)) {
            Patient existingPatient = patientStore.getCanonicalPatient(patient);
            if (!existingPatient.equals(patient)) {
                logger.error(
                        "Patient already exists with different details, existing patient: {}, submitted patient: {}",
                        existingPatient, patient);
                return createResponse(
                        SubmitClaimResult.SUBMIT_CLAIM_RESULT_PATIENT_WITH_SAME_ID_ALREADY_EXISTS);
            }
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
        Instant submittedAt =
                sendTimeSeconds == 0 ? Instant.now() : Instant.ofEpochSecond(sendTimeSeconds);
        claimStore.addClaim(claim, submittedAt);
        return createResponse(SubmitClaimResult.SUBMIT_CLAIM_RESULT_SUCCESS);
    }

    @Override
    public void notifyRemittance(NotifyRemittanceRequest request,
            StreamObserver<NotifyRemittanceResponse> observer) {
        Remittance remittance = request.getRemittance();
        logger.info("Received remittance for claim ID: {}", remittance.getClaimId());
        Instant responseReceivedAt = request.getSendTimeSeconds() == 0 ? Instant.now()
                : Instant.ofEpochSecond(request.getSendTimeSeconds());
        claimStore.addResponse(remittance.getClaimId(), remittance, responseReceivedAt);
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
        ImmutableList<PayerId> payerIds = request.getPayerFilterList().size() == 0 ? allPayerIds()
                : ImmutableList.copyOf(request.getPayerFilterList());
        ImmutableList<AccountsReceivableBucket> buckets =
                request.getBucketList().size() == 0 ? ImmutableList.of(catchAllBucket())
                        : ImmutableList.copyOf(request.getBucketList());
        GetPayerAccountsReceivableResponse response =
                payerPayments.getPayerAccountsReceivable(payerIds, buckets);
        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getPatientAccountsReceivable(GetPatientAccountsReceivableRequest request,
            StreamObserver<GetPatientAccountsReceivableResponse> observer) {
        logger.info("Received patient accounts receivable request");
        ImmutableSet<PatientId> patientIds =
                request.getPatientFilterList().size() == 0 ? patientStore.getAllPatientIds()
                        : request.getPatientFilterList().stream().map(PatientId::parse)
                                .collect(toImmutableSet());
        GetPatientAccountsReceivableResponse response =
                patientPayments.getPatientAccountsReceivable(patientIds);
        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getPatientClaims(GetPatientClaimsRequest request,
            StreamObserver<GetPatientClaimsResponse> observer) {
        logger.info("Received patient claims request");
        GetPatientClaimsResponse response = getPatientClaimsInternal(request.getPatientFilter());
        observer.onNext(response);
        observer.onCompleted();
    }

    private GetPatientClaimsResponse getPatientClaimsInternal(String patientIdFilter) {
        if (patientIdFilter == null || patientIdFilter.isEmpty()) {
            return GetPatientClaimsResponse.newBuilder().setError(
                    GetPatientClaimsResponse.GetPatientClaimError.GET_PATIENT_CLAIM_ERROR_NO_PATIENT_FILTER_SPECIFIED)
                    .build();
        }
        if (!PatientId.isValid(patientIdFilter)) {
            return GetPatientClaimsResponse.newBuilder().setError(
                    GetPatientClaimsResponse.GetPatientClaimError.GET_PATIENT_CLAIM_ERROR_INVALID_PATIENT_ID)
                    .build();
        }
        PatientId patientId = PatientId.parse(patientIdFilter);
        if (!patientStore.containsPatient(patientId)) {
            return GetPatientClaimsResponse.newBuilder().setError(
                    GetPatientClaimsResponse.GetPatientClaimError.GET_PATIENT_CLAIM_ERROR_PATIENT_NOT_FOUND)
                    .build();
        }
        Patient patient = patientStore.getPatient(patientId);
        List<PatientClaimRow> rows = new ArrayList<>();
        for (PayerClaim claim : claimStore.getPatientClaims(patientId)) {
            rows.add(createPatientClaimRow(patient, claim));
        }
        return GetPatientClaimsResponse.newBuilder().addAllRow(rows).build();
    }

    private PatientClaimRow createPatientClaimRow(Patient patient, PayerClaim claim) {
        PatientClaimRow.Builder row =
                PatientClaimRow.newBuilder().setPatient(patient).setClaimId(claim.getClaimId());
        ClaimProcessingInfo processingInfo = claimStore.getProcessingInfo(claim.getClaimId());
        if (processingInfo.closedAt().isPresent()) {
            return row.setStatus(GetPatientClaimsResponse.ClaimStatus.FULLY_PAID)
                    .setBalance(zeroOutstandingBalance()).build();
        }
        if (processingInfo.responseReceivedAt().isPresent()) {
            return row.setStatus(GetPatientClaimsResponse.ClaimStatus.REMITTENCE_RECEIVED)
                    .setBalance(patientPayments.getOutstandingPatientBalance(claim.getClaimId())
                            .toProto())
                    .build();
        }
        return row.setStatus(GetPatientClaimsResponse.ClaimStatus.SUBMITTED_TO_PAYER).build();
    }

    private static PatientBalance zeroOutstandingBalance() {
        return PatientBalance.newBuilder().setOutstandingCopay(CurrencyAmount.ZERO.toProto())
                .setOutstandingCoinsurance(CurrencyAmount.ZERO.toProto())
                .setOutstandingDeductible(CurrencyAmount.ZERO.toProto()).build();
    }

    @Override
    public void submitPatientPayment(SubmitPatientPaymentRequest request,
            StreamObserver<SubmitPatientPaymentResponse> observer) {
        SubmitPatientPaymentResult result = patientPayments.payClaim(request.getClaimId(),
                CurrencyAmount.fromProto(request.getAmount()));
        observer.onNext(SubmitPatientPaymentResponse.newBuilder().setResult(result).build());
        observer.onCompleted();
    }

    private static SubmitClaimResponse createResponse(SubmitClaimResult result) {
        return SubmitClaimResponse.newBuilder().setResult(result).build();
    }


    private static ImmutableList<PayerId> allPayerIds() {
        var payerIds = ImmutableList.copyOf(PayerId.values()).stream()
                .filter(payerId -> payerId != PayerId.PAYER_ID_UNSPECIFIED)
                .filter(payerId -> payerId != PayerId.UNRECOGNIZED).collect(toImmutableList());
        System.out.println("All payer IDs: " + payerIds);
        return payerIds;
    }

    private static AccountsReceivableBucket catchAllBucket() {
        // The default bucket will get interpreted as start time EPOCH and end time NOW, hence
        // covering all time
        return AccountsReceivableBucket.newBuilder().build();
    }
}

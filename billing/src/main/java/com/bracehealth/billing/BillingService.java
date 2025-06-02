package com.bracehealth.billing;

import com.bracehealth.shared.GetPayerAccountsReceivableRequest;
import com.bracehealth.shared.GetPayerAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientAccountsReceivableRequest;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.NotifyRemittanceResponse.NotifyRemittanceResult;
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
import com.bracehealth.billing.CurrencyUtil.CurrencyAmount;
import com.bracehealth.shared.BillingServiceGrpc;
import com.google.common.collect.ImmutableList;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.stream.Collectors;

@GrpcService
public class BillingService extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingService.class);
    private final PayerPaymentHelper payerPayments;
    private final PatientPaymentHelper patientPayments;
    private final ClaimStore claimStore;
    private final ClearingHouseClient clearingHouseClient;

    @Autowired
    public BillingService(PayerPaymentHelper payerPayments, PatientPaymentHelper patientPayments,
            ClaimStore claimStore, ClearingHouseClient clearingHouseClient) {
        this.payerPayments = payerPayments;
        this.patientPayments = patientPayments;
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
        ImmutableList<PayerId> payerIds = request.getPayerFilterList().size() == 0
                ? ImmutableList.copyOf(claimStore.getClaimsByPayer().keySet())
                : ImmutableList.copyOf(request.getPayerFilterList());
        GetPayerAccountsReceivableResponse response = payerPayments.getPayerAccountsReceivable(
                payerIds, ImmutableList.copyOf(request.getBucketList()));
        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getPatientAccountsReceivable(GetPatientAccountsReceivableRequest request,
            StreamObserver<GetPatientAccountsReceivableResponse> observer) {
        logger.info("Received patient accounts receivable request");
        ImmutableList<Patient> patients = request.getPatientFilterList().size() == 0
                ? ImmutableList.copyOf(claimStore.getClaimsByPatient().keySet())
                : ImmutableList.copyOf(claimStore.getClaimsByPatient().keySet().stream().filter(
                        patient -> request.getPatientFilterList().contains(toPatientId(patient)))
                        .collect(Collectors.toList()));
        GetPatientAccountsReceivableResponse response =
                patientPayments.getPatientAccountsReceivable(patients);
        observer.onNext(response);
        observer.onCompleted();
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

    private static String toPatientId(Patient patient) {
        return patient.getFirstName().toLowerCase() + "_" + patient.getLastName().toLowerCase()
                + "_" + patient.getDob();
    }
}

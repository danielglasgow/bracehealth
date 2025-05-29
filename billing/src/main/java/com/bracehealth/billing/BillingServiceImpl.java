package com.bracehealth.billing;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class BillingServiceImpl extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingServiceImpl.class);

    @Override
    public void submitClaim(SubmitClaimRequest request, StreamObserver<SubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim: {}", claim);
            
            SubmitClaimResponse response = SubmitClaimResponse.newBuilder()
                    .setSuccess(true)
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing claim submission", e);
            responseObserver.onError(e);
        }
    }

    private static String claimToString(PayerClaim claim) {
        return String.format("Claim ID: %s, Place of Service Code: %s, Insurance: %s, Patient: %s, Organization: %s, Rendering Provider: %s, Service Lines: %s",
                claim.getClaimId(),
                claim.getPlaceOfServiceCode(),
                claim.getInsurance(),
                claim.getPatient(),
                claim.getOrganization(),
                claim.getRenderingProvider(),
                claim.getServiceLinesList());
    }
} 
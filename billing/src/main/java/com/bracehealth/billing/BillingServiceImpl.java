package com.bracehealth.billing;

import com.bracehealth.shared.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@GrpcService
public class BillingServiceImpl extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BillingServiceImpl.class);
    private final ClaimStore claimStore;

    @Autowired
    public BillingServiceImpl(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    @Override
    public void submitClaim(SubmitClaimRequest request, StreamObserver<SubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim submission for claim ID: {}", claim.getClaimId());

            claimStore.addClaim(claim);

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

    @Override
    public void receiveRemittance(ReceiveRemittanceRequest request,
            StreamObserver<ReceiveRemittanceResponse> responseObserver) {
        try {
            RemittanceResponse remittance = request.getRemittance();
            logger.info("Received remittance for claim ID: {}, paid amount: {}",
                    remittance.getClaimId(),
                    remittance.getPayerPaidAmount());

            claimStore.addResponse(remittance.getClaimId(), remittance);

            ReceiveRemittanceResponse response = ReceiveRemittanceResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing remittance", e);
            responseObserver.onError(e);
        }
    }
}
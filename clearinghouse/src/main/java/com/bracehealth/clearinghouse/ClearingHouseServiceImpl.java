package com.bracehealth.clearinghouse;

import com.bracehealth.shared.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class ClearingHouseServiceImpl extends ClearingHouseServiceGrpc.ClearingHouseServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ClearingHouseServiceImpl.class);

    @Override
    public void submitClaim(SubmitClaimRequest request, StreamObserver<SubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim submission for claim ID: {} to payer: {}",
                    claim.getClaimId(),
                    claim.getInsurance().getPayerId());

            // TODO: Implement actual clearinghouse submission logic here
            // This would typically involve:
            // 1. Validating the claim
            // 2. Formatting the claim according to payer requirements
            // 3. Submitting to the appropriate payer
            // 4. Handling the response

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
}
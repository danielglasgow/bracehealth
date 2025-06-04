package com.bracehealth.clearinghouse;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import com.bracehealth.shared.ClearingHouseServiceGrpc;
import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;
import com.bracehealth.shared.BillingServiceGrpc;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.PayerConfig;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.NotifyRemittanceResponse.NotifyRemittanceResult;
import com.bracehealth.shared.RemittanceUtil;
import static com.bracehealth.shared.PayerConfig.PAYER_CONFIGS;

@GrpcService
public class ClearingHouseService extends ClearingHouseServiceGrpc.ClearingHouseServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ClearingHouseService.class);
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(/* corePoolSize= */ 1);
    private final static Random random = new Random();

    @GrpcClient("billing-service")
    private BillingServiceGrpc.BillingServiceBlockingStub billingService;

    @Override
    public void processClaim(ProcessClaimRequest request,
            StreamObserver<ProcessClaimResponse> responseObserver) {
        PayerClaim claim = request.getClaim();
        logger.info("Received claim submission for claim ID: {} to payer: {}", claim.getClaimId(),
                claim.getInsurance().getPayerId());
        PayerConfig payerConfig = PAYER_CONFIGS.get(claim.getInsurance().getPayerId());
        if (payerConfig == null) {
            logger.error("Payer {} not supported", claim.getInsurance().getPayerId());
            ProcessClaimResponse response =
                    ProcessClaimResponse.newBuilder().setSuccess(false).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }
        submitToMockWorkQueue(claim, payerConfig);
        ProcessClaimResponse response = ProcessClaimResponse.newBuilder().setSuccess(true).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();

    }

    private void submitToMockWorkQueue(PayerClaim claim, PayerConfig payerConfig) {
        int delaySeconds = random.nextInt(
                payerConfig.maxResponseTimeSeconds() - payerConfig.minResponseTimeSeconds() + 1)
                + payerConfig.minResponseTimeSeconds();
        logger.info("Scheduling claim {} for processing in {} seconds", claim.getClaimId(),
                delaySeconds);
        Remittance remittance = RemittanceUtil.generateRandomRemittance(claim);
        scheduler.schedule(() -> callbackToBillingService(remittance), delaySeconds,
                TimeUnit.SECONDS);
    }

    private void callbackToBillingService(Remittance remittance) {
        logger.info("Callbacking for claim {}", remittance.getClaimId());
        NotifyRemittanceRequest request =
                NotifyRemittanceRequest.newBuilder().setRemittance(remittance).build();
        NotifyRemittanceResponse response = billingService.notifyRemittance(request);
        if (response.getResult() != NotifyRemittanceResult.NOTIFY_REMITTANCE_RESULT_SUCCESS) {
            logger.error("Failed to submit remittance for claim {}. Result: {}",
                    remittance.getClaimId(), response.getResult());
        }
        logger.info("Successfully callbacked for claim {}", remittance.getClaimId());
    }

}

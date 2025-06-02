package com.bracehealth.billing;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bracehealth.shared.BillingServiceGrpc;
import com.bracehealth.shared.NotifyRemittanceRequest;
import com.bracehealth.shared.NotifyRemittanceResponse;
import com.bracehealth.shared.NotifyRemittanceResponse.NotifyRemittanceResult;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.PayerConfig;
import com.bracehealth.shared.PayerId;
import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.RemittanceUtil;
import com.google.common.collect.ImmutableMap;


/**
 * An in-memory clearinghouse client.
 * 
 * This is used to simulate a clearinghouse.
 * 
 * It will process claims and then schedule a callback to the billing service.
 * 
 * The callback will be a loopback call to the billing service.
 */
class InMemoryClearingHouseClient implements ClearingHouseClient {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryClearingHouseClient.class);
    private static final ImmutableMap<PayerId, PayerConfig> PAYER_CONFIGS = ImmutableMap.of(
            PayerId.MEDICARE, PayerConfig.builder().payerId(PayerId.MEDICARE)
                    .minResponseTimeSeconds(1).maxResponseTimeSeconds(10).build(),
            PayerId.UNITED_HEALTH_GROUP,
            PayerConfig.builder().payerId(PayerId.UNITED_HEALTH_GROUP).minResponseTimeSeconds(1)
                    .maxResponseTimeSeconds(10).build(),
            PayerId.ANTHEM, PayerConfig.builder().payerId(PayerId.ANTHEM).minResponseTimeSeconds(1)
                    .maxResponseTimeSeconds(10).build());


    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(/* corePoolSize= */ 1);
    private final static Random random = new Random();

    private final BillingServiceGrpc.BillingServiceBlockingStub billingService;

    public InMemoryClearingHouseClient(
            BillingServiceGrpc.BillingServiceBlockingStub billingService) {
        this.billingService = billingService;
    }

    @Override
    public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
        PayerClaim claim = request.getClaim();
        PayerConfig payerConfig = PAYER_CONFIGS.get(request.getClaim().getInsurance().getPayerId());
        if (payerConfig == null) {
            logger.error("Payer {} not supported", claim.getInsurance().getPayerId());
            return ProcessClaimResponse.newBuilder().setSuccess(false).build();
        }

        int delaySeconds = random.nextInt(
                payerConfig.maxResponseTimeSeconds() - payerConfig.minResponseTimeSeconds() + 1)
                + payerConfig.minResponseTimeSeconds();
        logger.info("Scheduling claim {} for processing in {} seconds", claim.getClaimId(),
                delaySeconds);
        Remittance remittance = RemittanceUtil.generateRandomRemittance(claim);
        scheduler.schedule(() -> callbackToBillingService(remittance), delaySeconds,
                TimeUnit.SECONDS);
        return ProcessClaimResponse.newBuilder().setSuccess(true).build();
    }

    // Loopback call to self
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

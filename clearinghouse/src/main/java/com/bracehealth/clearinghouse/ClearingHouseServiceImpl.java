package com.bracehealth.clearinghouse;

import com.google.common.collect.ImmutableMap;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.devh.boot.grpc.client.inject.GrpcClient;
import com.bracehealth.shared.ClearingHouseServiceGrpc;
import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.ClearingHouseSubmitClaimResponse;
import com.bracehealth.shared.PayerId;
import com.bracehealth.shared.BillingServiceGrpc;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.RemittanceResponse;
import com.bracehealth.shared.SubmitRemittanceRequest;
import com.bracehealth.shared.SubmitRemittanceResponse;
import com.bracehealth.shared.ServiceLine;

@GrpcService
public class ClearingHouseServiceImpl
        extends ClearingHouseServiceGrpc.ClearingHouseServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ClearingHouseServiceImpl.class);
    // TODO: Tune corePoolSize appropriately
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(/* corePoolSize= */ 1);
    private final Random random = new Random();

    @GrpcClient("billing-service")
    private BillingServiceGrpc.BillingServiceBlockingStub billingService;

    private final ImmutableMap<PayerId, PayerConfig> payerConfigs = ImmutableMap.of(
            PayerId.MEDICARE, PayerConfig.builder().payerId(PayerId.MEDICARE)
                    .minResponseTimeSeconds(1).maxResponseTimeSeconds(10).build(),
            PayerId.UNITED_HEALTH_GROUP,
            PayerConfig.builder().payerId(PayerId.UNITED_HEALTH_GROUP).minResponseTimeSeconds(1)
                    .maxResponseTimeSeconds(10).build(),
            PayerId.ANTHEM, PayerConfig.builder().payerId(PayerId.ANTHEM).minResponseTimeSeconds(1)
                    .maxResponseTimeSeconds(10).build());

    @Override
    public void submitClaim(SubmitClaimRequest request,
            StreamObserver<ClearingHouseSubmitClaimResponse> responseObserver) {
        try {
            PayerClaim claim = request.getClaim();
            logger.info("Received claim submission for claim ID: {} to payer: {}",
                    claim.getClaimId(), claim.getInsurance().getPayerId());
            PayerConfig payerConfig = payerConfigs.get(claim.getInsurance().getPayerId());
            if (payerConfig == null) {
                logger.error("Payer {} not supported", claim.getInsurance().getPayerId());
                ClearingHouseSubmitClaimResponse response =
                        ClearingHouseSubmitClaimResponse.newBuilder().setSuccess(false).build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            submitToMockWorkQueue(claim, payerConfig);

            ClearingHouseSubmitClaimResponse response =
                    ClearingHouseSubmitClaimResponse.newBuilder().setSuccess(true).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error processing claim submission", e);
            responseObserver.onError(e);
        }
    }

    private void submitToMockWorkQueue(PayerClaim claim, PayerConfig payerConfig) {
        int delaySeconds = random.nextInt(
                payerConfig.maxResponseTimeSeconds() - payerConfig.minResponseTimeSeconds() + 1)
                + payerConfig.minResponseTimeSeconds();

        logger.info("Scheduling claim {} for processing in {} seconds", claim.getClaimId(),
                delaySeconds);

        scheduler.schedule(() -> {
            logger.info("Processing claim {} after {} second delay", claim.getClaimId(),
                    delaySeconds);
            try {
                RemittanceResponse response = generateRandomRemittanceResponse(claim);

                logger.info("Submitting remittance {}", response.getClaimId());
                SubmitRemittanceRequest request =
                        SubmitRemittanceRequest.newBuilder().setRemittance(response).build();
                SubmitRemittanceResponse remittanceResponse =
                        billingService.submitRemittance(request);
                if (!remittanceResponse.getSuccess()) {
                    logger.error("Failed to submit remittance for claim {}", claim.getClaimId());
                } else {
                    logger.info("Successfully submitted remittance for claim {}",
                            claim.getClaimId());
                }
            } catch (Exception e) {
                logger.error("Error submitting remittance for claim {}", claim.getClaimId(), e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private static RemittanceResponse generateRandomRemittanceResponse(PayerClaim claim) {
        RemittanceResponse.Builder response =
                RemittanceResponse.newBuilder().setClaimId(claim.getClaimId());
        double totalAmountOwed = 0.0;
        for (ServiceLine serviceLine : claim.getServiceLinesList()) {
            // TODO: Handle currency
            totalAmountOwed += serviceLine.getUnitChargeAmount() * serviceLine.getUnits();
        }

        double[] percentages = generateNDistributionThatSumToOne(5);
        double payerPaidAmount = roundToTwoDecimals(totalAmountOwed * percentages[0]);
        double coinsuranceAmount = roundToTwoDecimals(totalAmountOwed * percentages[1]);
        double copayAmount = roundToTwoDecimals(totalAmountOwed * percentages[2]);
        double deductibleAmount = roundToTwoDecimals(totalAmountOwed * percentages[3]);
        double notAllowedAmount = roundToTwoDecimals(totalAmountOwed * percentages[4]);

        double adjustment = totalAmountOwed - payerPaidAmount - coinsuranceAmount - copayAmount
                - deductibleAmount - notAllowedAmount;
        payerPaidAmount += adjustment;
        logger.info("Orignal amount {}, Adjustment {}, Sum post adjustment: {}", totalAmountOwed,
                adjustment, payerPaidAmount + coinsuranceAmount + copayAmount + deductibleAmount
                        + notAllowedAmount);

        response.setPayerPaidAmount(payerPaidAmount);
        response.setCoinsuranceAmount(coinsuranceAmount);
        response.setCopayAmount(copayAmount);
        response.setDeductibleAmount(deductibleAmount);
        response.setNotAllowedAmount(notAllowedAmount);

        return response.build();
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static double[] generateNDistributionThatSumToOne(int n) {
        Random rand = new Random();
        double[] points = new double[n];
        points[0] = 0.0;
        points[n - 1] = 1.0;
        for (int i = 0; i < n; i++) {
            points[i] = rand.nextDouble();
        }
        Arrays.sort(points);
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            double nextPoint = i + 1 < n ? points[i + 1] : 1.0;
            result[i] = nextPoint - points[i];
        }
        return result;
    }

    private record PayerConfig(PayerId payerId, int minResponseTimeSeconds,
            int maxResponseTimeSeconds) {

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private PayerId payerId;
            private int minResponseTimeSeconds;
            private int maxResponseTimeSeconds;

            public Builder payerId(PayerId payerId) {
                this.payerId = payerId;
                return this;
            }

            public Builder minResponseTimeSeconds(int minResponseTimeSeconds) {
                this.minResponseTimeSeconds = minResponseTimeSeconds;
                return this;
            }

            public Builder maxResponseTimeSeconds(int maxResponseTimeSeconds) {
                this.maxResponseTimeSeconds = maxResponseTimeSeconds;
                return this;
            }

            public PayerConfig build() {
                return new PayerConfig(payerId, minResponseTimeSeconds, maxResponseTimeSeconds);
            }
        }
    }
}

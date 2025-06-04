package com.bracehealth.shared;

import com.google.common.collect.ImmutableMap;

public record PayerConfig(PayerId payerId, int minResponseTimeSeconds, int maxResponseTimeSeconds) {

    private static final int MIN_RESPONSE_TIME_SECONDS = 1;
    private static final int MAX_RESPONSE_TIME_SECONDS = 60;
    public static final ImmutableMap<PayerId, PayerConfig> PAYER_CONFIGS = ImmutableMap.of(
            PayerId.MEDICARE, PayerConfig.builder().payerId(PayerId.MEDICARE)
                    .minResponseTimeSeconds(0).maxResponseTimeSeconds(30).build(),
            PayerId.UNITED_HEALTH_GROUP,
            PayerConfig.builder().payerId(PayerId.UNITED_HEALTH_GROUP).minResponseTimeSeconds(30)
                    .maxResponseTimeSeconds(60).build(),
            PayerId.ANTHEM, PayerConfig.builder().payerId(PayerId.ANTHEM).minResponseTimeSeconds(30)
                    .maxResponseTimeSeconds(120).build());


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

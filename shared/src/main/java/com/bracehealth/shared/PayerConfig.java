package com.bracehealth.shared;

public record PayerConfig(PayerId payerId, int minResponseTimeSeconds, int maxResponseTimeSeconds) {

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

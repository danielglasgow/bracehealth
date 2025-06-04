package com.bracehealth.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;
import com.bracehealth.shared.ClearingHouseServiceGrpc;
import com.bracehealth.shared.BillingServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Configuration
public class ClearingHouseClientConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "clearinghouse.mode", havingValue = "grpc")
    public ClearingHouseClient grpcClearingHouseClient(
            com.bracehealth.shared.ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub) {
        return new ClearingHouseClient() {
            @Override
            public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
                return clearingHouseStub.processClaim(request);
            }
        };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "clearinghouse.mode", havingValue = "noop")
    public ClearingHouseClient noOpClearingHouseClient() {
        return new ClearingHouseClient() {
            @Override
            public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
                return ProcessClaimResponse.newBuilder().setSuccess(true).build();
            }
        };
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "clearinghouse.mode", havingValue = "inmemory",
            matchIfMissing = true)
    public ClearingHouseClient inMemoryClearingHouseClient(
            BillingServiceGrpc.BillingServiceBlockingStub billingServiceStub) {
        return new InMemoryClearingHouseClient(billingServiceStub);
    }

    @GrpcClient("clearinghouse-service")
    private ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub;

    @Bean
    public ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub() {
        return clearingHouseStub;
    }

    // Loopback call to self
    @GrpcClient("billing-service")
    private BillingServiceGrpc.BillingServiceBlockingStub billingServiceStub;

    @Bean
    public BillingServiceGrpc.BillingServiceBlockingStub billingServiceStub() {
        return billingServiceStub;
    }
}

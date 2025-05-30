package com.bracehealth.billing;

import com.bracehealth.shared.ClearingHouseServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfig {

    @GrpcClient("clearinghouse-service")
    private ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub;

    @Bean
    public ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub() {
        return clearingHouseStub;
    }
}

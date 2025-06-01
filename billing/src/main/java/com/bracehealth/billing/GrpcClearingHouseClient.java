package com.bracehealth.billing;

import com.bracehealth.shared.ClearingHouseServiceGrpc;
import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;
import org.springframework.stereotype.Component;

@Component
public class GrpcClearingHouseClient implements ClearingHouseClient {
    private final ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub;

    public GrpcClearingHouseClient(
            ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub) {
        this.clearingHouseStub = clearingHouseStub;
    }

    @Override
    public ProcessClaimResponse processClaim(ProcessClaimRequest request) {
        return clearingHouseStub.processClaim(request);
    }
}

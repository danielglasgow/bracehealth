package com.bracehealth.billing;

import com.bracehealth.shared.ClearingHouseServiceGrpc;
import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.SubmitClaimResponse;
import org.springframework.stereotype.Component;

@Component
public class GrpcClearingHouseClient implements ClearingHouseClient {
    private final ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub;

    public GrpcClearingHouseClient(
            ClearingHouseServiceGrpc.ClearingHouseServiceBlockingStub clearingHouseStub) {
        this.clearingHouseStub = clearingHouseStub;
    }

    @Override
    public SubmitClaimResponse submitClaim(SubmitClaimRequest request) {
        return clearingHouseStub.submitClaim(request);
    }
}

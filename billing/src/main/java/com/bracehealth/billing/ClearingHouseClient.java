package com.bracehealth.billing;

import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.ClearingHouseSubmitClaimResponse;

// Necessary for testing
public interface ClearingHouseClient {
    ClearingHouseSubmitClaimResponse submitClaim(SubmitClaimRequest request);
}

package com.bracehealth.billing;

import com.bracehealth.shared.SubmitClaimRequest;
import com.bracehealth.shared.SubmitClaimResponse;

// Necessary for testing
public interface ClearingHouseClient {
    SubmitClaimResponse submitClaim(SubmitClaimRequest request);
}

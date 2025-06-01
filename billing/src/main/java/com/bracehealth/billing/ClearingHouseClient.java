package com.bracehealth.billing;

import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;

// Necessary for testing
public interface ClearingHouseClient {
    ProcessClaimResponse processClaim(ProcessClaimRequest request);
}

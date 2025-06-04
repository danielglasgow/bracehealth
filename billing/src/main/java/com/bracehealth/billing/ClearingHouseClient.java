package com.bracehealth.billing;

import com.bracehealth.shared.ProcessClaimRequest;
import com.bracehealth.shared.ProcessClaimResponse;

/**
 * Interface for the clearinghouse client.
 * 
 * This is used to submit claims to the clearinghouse.
 * 
 * There are several implementations, based on how the user wants to run the simulation:
 * <ul>
 * <li>No-op: Claims submitted never get a callback
 * <li>In-memory: An "in-memory" clearinghouse processes claims and notifies remittances
 * <li>GRPC: Makes a call to an actual clearinghouse service (in this case, still a
 * psuedo-clearinghouse)
 * </ul>
 */
public interface ClearingHouseClient {
    ProcessClaimResponse processClaim(ProcessClaimRequest request);
}

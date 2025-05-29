package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.bracehealth.shared.RemittanceResponse;
import com.bracehealth.shared.PayerClaim;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.time.Instant;

/**
 * Stores (in memory) claims that have been submitted.
 * 
 */
public class ClaimStore {

    // Note, singleton is not great / should be done with DI framework
    private static ClaimStore INSTANCE = null;

    private final ConcurrentMap<String, Claim> claims;

    private record ClearingHouseResponse(RemittanceResponse remittanceResponse, Instant receivedAt) {
    }

    private record Claim(PayerClaim claim, ClaimStatus status, Optional<ClearingHouseResponse> clearingHouseResponse) {
    }

    @VisibleForTesting
    ClaimStore(ImmutableMap<String, Claim> claims) {
        this.claims = new ConcurrentHashMap<>(claims);
    }

    public static ClaimStore getInstance() {
        if (INSTANCE == null) {
            // TODO: Read in claims from DB
            INSTANCE = new ClaimStore(ImmutableMap.of());
        }
        return INSTANCE;
    }

    public void addClaim(PayerClaim claim) {
        claims.put(claim.getClaimId(), new Claim(claim, ClaimStatus.PENDING, Optional.empty()));
    }

    public void addResponse(String claimId, RemittanceResponse remittanceResponse) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim not found");
        }
        claims.computeIfPresent(claimId, (id, claim) -> {
            return new Claim(claim.claim(), ClaimStatus.RESPONSE_RECEIVED,
                    Optional.of(new ClearingHouseResponse(remittanceResponse, Instant.now())));
        });
    }

    public enum ClaimStatus {
        PENDING,
        RESPONSE_RECEIVED,
    }
}

package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.bracehealth.shared.RemittanceResponse;
import com.bracehealth.shared.PayerClaim;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.time.Instant;
import java.util.Map;

/**
 * Stores (in memory) claims that have been submitted.
 * 
 * On application shutdown, the claims are persisted to disk
 */
public class ClaimStore {

    private final ConcurrentMap<String, Claim> claims;

    record ClearingHouseResponse(RemittanceResponse remittanceResponse, Instant receivedAt) {
    }

    record Claim(PayerClaim claim, ClaimStatus status, Optional<ClearingHouseResponse> clearingHouseResponse) {
    }

    public ClaimStore() {
        this.claims = new ConcurrentHashMap<>();
    }

    @VisibleForTesting
    ClaimStore(Map<String, Claim> claims) {
        this.claims = new ConcurrentHashMap<>(claims);
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

    public Map<String, Claim> getData() {
        return ImmutableMap.copyOf(claims);
    }

    enum ClaimStatus {
        PENDING,
        RESPONSE_RECEIVED,
    }
}

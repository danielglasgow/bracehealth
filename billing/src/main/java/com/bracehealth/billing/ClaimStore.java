package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.bracehealth.shared.RemittanceResponse;
import com.bracehealth.shared.PayerClaim;
import java.util.Optional;
import java.time.Instant;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Base64;
import java.nio.file.Files;
import com.google.common.collect.ImmutableMap;

import java.util.stream.Collectors;

/**
 * Stores (in memory) claims that have been submitted.
 * 
 * On application shutdown, the claims are persisted to disk
 */
public class ClaimStore {

    private static final Logger logger = LoggerFactory.getLogger(ClaimStore.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storagePath;
    private final ConcurrentMap<String, Claim> claims;

    record ClearingHouseResponse(RemittanceResponse remittanceResponse, Instant receivedAt) {
    }

    record Claim(PayerClaim claim, ClaimStatus status,
            Optional<ClearingHouseResponse> clearingHouseResponse) {
    }

    public ClaimStore(Path storagePath, Map<String, Claim> claims) {
        this.storagePath = storagePath;
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

    public ImmutableMap<String, Claim> getClaims() {
        return ImmutableMap.copyOf(claims);
    }

    public void writeToDisk() {
        try {
            Map<String, JsonClaim> jsonClaims = claims.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            e -> new JsonClaim(
                                    Base64.getEncoder()
                                            .encodeToString(e.getValue().claim().toByteArray()),
                                    e.getValue().status().name(),
                                    e.getValue().clearingHouseResponse()
                                            .map(r -> new JsonResponse(
                                                    Base64.getEncoder().encodeToString(
                                                            r.remittanceResponse().toByteArray()),
                                                    r.receivedAt().toString()))
                                            .orElse(null))));
            System.out.println("Writing claims to disk: " + jsonClaims);
            objectMapper.writeValue(storagePath.toFile(), jsonClaims);
            logger.info("Successfully persisted claims to disk");
        } catch (IOException e) {
            logger.error("Failed to write claims to disk", e);
        }
    }

    public static ClaimStore newInstanceFromDisk(Path storagePath) {
        try {
            if (Files.exists(storagePath)) {
                Map<String, JsonClaim> jsonClaims =
                        objectMapper.readValue(storagePath.toFile(), objectMapper.getTypeFactory()
                                .constructMapType(Map.class, String.class, JsonClaim.class));

                Map<String, ClaimStore.Claim> claims = jsonClaims.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> new ClaimStore.Claim(
                                parsePayerClaim(e.getValue().paymentClaim()),
                                ClaimStore.ClaimStatus.valueOf(e.getValue().status()),
                                Optional.ofNullable(e.getValue().clearingHouseResponse())
                                        .map(r -> new ClaimStore.ClearingHouseResponse(
                                                parseRemittanceResponse(r.remittanceResponse()),
                                                Instant.parse(r.receivedAt()))))));

                return new ClaimStore(storagePath, claims);
            }
        } catch (IOException e) {
            logger.warn("Failed to load claims from disk, starting with empty store", e);
        }
        return new ClaimStore(storagePath, ImmutableMap.of());

    }

    private static PayerClaim parsePayerClaim(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return PayerClaim.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PayerClaim", e);
        }
    }

    private static RemittanceResponse parseRemittanceResponse(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return RemittanceResponse.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse RemittanceResponse", e);
        }
    }

    private record JsonClaim(String paymentClaim, String status,
            JsonResponse clearingHouseResponse) {
    }

    private record JsonResponse(String remittanceResponse, String receivedAt) {
    }

    enum ClaimStatus {
        PENDING, RESPONSE_RECEIVED,
    }
}

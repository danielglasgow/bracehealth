package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.Patient;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Files;
import com.google.common.collect.ImmutableList;
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

    public ClaimStore(Path storagePath, Map<String, Claim> claims) {
        this.storagePath = storagePath;
        this.claims = new ConcurrentHashMap<>(claims);
    }

    public boolean containsClaim(String claimId) {
        return claims.containsKey(claimId);
    }

    public void addClaim(PayerClaim claim) {
        claims.put(claim.getClaimId(),
                new Claim(claim, Instant.now(), ClaimStatus.PENDING, Optional.empty(), 0.0));
    }

    public void addResponse(String claimId, Remittance remittanceResponse) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim not found");
        }
        claims.computeIfPresent(claimId, (id, claim) -> {
            var clearingHouseResponse =
                    new ClearingHouseResponse(remittanceResponse, Instant.now());
            return claim.updateResponse(clearingHouseResponse)
                    .updateStatus(ClaimStatus.RESPONSE_RECEIVED);
        });
    }

    public void addPatientPayment(String claimId, double amount) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim not found");
        }
        claims.computeIfPresent(claimId, (id, claim) -> {
            return claim.updatePatientPayment(claim.patientPayment() + amount);
        });
    }

    public double getOutstandingPatientBalance(String claimId) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim not found");
        }
        Claim claim = claims.get(claimId);
        if (claim.status() != ClaimStatus.RESPONSE_RECEIVED) {
            return 0.0;
        }
        Optional<Remittance> remittance =
                claim.clearingHouseResponse().map(ClearingHouseResponse::remittanceResponse);
        double totalPatientResponsibility = remittance
                .map(r -> r.getCopayAmount() + r.getCoinsuranceAmount() + r.getDeductibleAmount())
                .orElse(0.0);
        return Math.max(0.0, totalPatientResponsibility - claim.patientPayment());
    }

    public ImmutableMap<String, Claim> getClaims() {
        return ImmutableMap.copyOf(claims);
    }

    public ImmutableMap<String, Claim> getPendingClaims() {
        ImmutableMap.Builder<String, Claim> builder = ImmutableMap.builder();
        for (Map.Entry<String, Claim> entry : claims.entrySet()) {
            if (entry.getValue().status() == ClaimStatus.PENDING) {
                builder.put(entry.getKey(), entry.getValue());
            }
        }
        return builder.build();
    }

    public ImmutableMap<Patient, ImmutableList<Claim>> getClaimsByPatient() {
        Map<Patient, List<Claim>> patientClaims = new HashMap<>();
        for (Claim claim : claims.values()) {
            Patient patient = claim.claim().getPatient();
            if (patientClaims.containsKey(patient)) {
                patientClaims.get(patient).add(claim);
            } else {
                List<Claim> claims = new ArrayList<>();
                claims.add(claim);
                patientClaims.put(patient, claims);
            }
        }
        return ImmutableMap.copyOf(patientClaims.entrySet().stream().collect(Collectors
                .toMap(Map.Entry::getKey, entry -> ImmutableList.copyOf(entry.getValue()))));
    }

    public void writeToDisk() {
        try {
            Map<String, JsonClaim> jsonClaims = claims.entrySet().stream().collect(
                    Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toJsonClaim()));
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
                ImmutableMap<String, Claim> claims = ImmutableMap.copyOf(
                        jsonClaims.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                                entry -> Claim.fromJsonClaim(entry.getValue()))));
                logger.info("Loaded {} claims from disk", claims.size());
                return new ClaimStore(storagePath, claims);
            }
        } catch (IOException e) {
            logger.warn("Failed to load claims from disk, starting with empty store", e);
        }
        return new ClaimStore(storagePath, ImmutableMap.of());
    }

    record ClearingHouseResponse(Remittance remittanceResponse, Instant receivedAt) {
        private static ClearingHouseResponse fromJsonResponse(JsonResponse jsonResponse) {
            return new ClearingHouseResponse(parseRemittance(jsonResponse.remittanceResponse()),
                    Instant.parse(jsonResponse.receivedAt()));
        }

        private JsonResponse toJsonResponse() {
            return new JsonResponse(
                    Base64.getEncoder().encodeToString(remittanceResponse().toByteArray()),
                    receivedAt().toString());
        }
    }

    record Claim(PayerClaim claim, Instant submittedAt, ClaimStatus status,
            Optional<ClearingHouseResponse> clearingHouseResponse, double patientPayment) {

        private Claim updateResponse(ClearingHouseResponse clearingHouseResponse) {
            return new Claim(claim(), submittedAt(), status(), Optional.of(clearingHouseResponse),
                    patientPayment());
        }

        private Claim updateStatus(ClaimStatus status) {
            return new Claim(claim(), submittedAt(), status, clearingHouseResponse(),
                    patientPayment());
        }

        private Claim updatePatientPayment(double patientPayment) {
            return new Claim(claim(), submittedAt(), status(), clearingHouseResponse(),
                    patientPayment);
        }

        private JsonClaim toJsonClaim() {
            return new JsonClaim(Base64.getEncoder().encodeToString(claim().toByteArray()),
                    submittedAt().toString(), status().name(),
                    clearingHouseResponse().map(ClearingHouseResponse::toJsonResponse).orElse(null),
                    patientPayment());
        }

        private static Claim fromJsonClaim(JsonClaim jsonClaim) {
            return new Claim(parsePayerClaim(jsonClaim.paymentClaim()),
                    Instant.parse(jsonClaim.submittedAt()), ClaimStatus.valueOf(jsonClaim.status()),
                    Optional.ofNullable(jsonClaim.clearingHouseResponse()).map(
                            ClearingHouseResponse::fromJsonResponse),
                    jsonClaim.patientPayment());
        }
    }

    private record JsonClaim(String paymentClaim, String submittedAt, String status,
            JsonResponse clearingHouseResponse, double patientPayment) {
    }

    private record JsonResponse(String remittanceResponse, String receivedAt) {
    }

    enum ClaimStatus {
        PENDING, RESPONSE_RECEIVED,
    }

    private static PayerClaim parsePayerClaim(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return PayerClaim.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse PayerClaim", e);
        }
    }

    private static Remittance parseRemittance(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return Remittance.parseFrom(bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Remittance", e);
        }
    }
}

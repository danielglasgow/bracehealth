package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.Patient;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.PayerId;
import java.util.Optional;
import java.time.Instant;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Files;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Collectors;

/**
 * Stores (in memory) claims that have been submitted.
 * 
 * On application shutdown, the claims are persisted to disk
 */
public class ClaimStore implements ApplicationListener<ContextClosedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ClaimStore.class);

    private final Path storagePath;
    private final ConcurrentMap<String, PayerClaim> claims;
    private final ConcurrentMap<String, PayerClaim> pendingClaims;
    private final ConcurrentMap<String, ClaimProcessingInfo> processingInfo;
    private final ConcurrentMap<String, Remittance> remittances;
    private final ConcurrentMap<String, Double> patientPayments;
    private final ConcurrentMap<Patient, ImmutableList<PayerClaim>> claimsByPatient;
    private final ConcurrentMap<PayerId, ImmutableList<PayerClaim>> claimsByPayer;

    public ClaimStore(Path storagePath) {
        this.storagePath = storagePath;
        this.claims = new ConcurrentHashMap<>();
        this.pendingClaims = new ConcurrentHashMap<>();
        this.processingInfo = new ConcurrentHashMap<>();
        this.remittances = new ConcurrentHashMap<>();
        this.patientPayments = new ConcurrentHashMap<>();
        this.claimsByPatient = new ConcurrentHashMap<>();
        this.claimsByPayer = new ConcurrentHashMap<>();
    }

    public boolean containsClaim(String claimId) {
        PayerClaim claim = claims.get(claimId);
        if (claim != null) {
            // Throw exception if claim is in invalid state
            checkClaimExists(claim.getClaimId());
            return true;
        }
        return false;
    }

    public void addClaim(PayerClaim claim, Instant submittedAt) {
        checkClaimDoesNotExist(claim);
        claims.putIfAbsent(claim.getClaimId(), claim);
        processingInfo.putIfAbsent(claim.getClaimId(), ClaimProcessingInfo.createNew(submittedAt));
        pendingClaims.putIfAbsent(claim.getClaimId(), claim);
        Patient patient = claim.getPatient();
        if (claimsByPatient.containsKey(patient)) {
            claimsByPatient.computeIfPresent(patient, (p, claims) -> ImmutableList
                    .<PayerClaim>builder().addAll(claims).add(claim).build());
        } else {
            claimsByPatient.computeIfAbsent(patient, p -> ImmutableList.of(claim));
        }
        PayerId payerId = claim.getInsurance().getPayerId();
        if (claimsByPayer.containsKey(payerId)) {
            claimsByPayer.computeIfPresent(payerId, (p, claims) -> ImmutableList
                    .<PayerClaim>builder().addAll(claims).add(claim).build());
        } else {
            claimsByPayer.computeIfAbsent(payerId, p -> ImmutableList.of(claim));
        }
    }


    public void addResponse(String claimId, Remittance remittance, Instant responseReceivedAt) {
        checkClaimExists(claimId);
        processingInfo.compute(claimId,
                (id, info) -> info.updateOnResponseReceived(responseReceivedAt));
        remittances.putIfAbsent(claimId, remittance);
        pendingClaims.remove(claimId);
    }

    public void addPatientPayment(String claimId, double amount) {
        checkClaimExists(claimId);
        if (patientPayments.containsKey(claimId)) {
            patientPayments.computeIfPresent(claimId, (id, payment) -> payment + amount);
        } else {
            patientPayments.computeIfAbsent(claimId, id -> amount);
        }
    }

    public ClaimProcessingInfo getProcessingInfo(String claimId) {
        return processingInfo.get(claimId);
    }

    public Remittance getRemittance(String claimId) {
        return remittances.get(claimId);
    }

    public double getPatientPayment(String claimId) {
        return patientPayments.getOrDefault(claimId, 0.0);
    }

    public ImmutableMap<String, PayerClaim> getClaims() {
        return ImmutableMap.copyOf(claims);
    }

    public ImmutableMap<Patient, ImmutableList<PayerClaim>> getClaimsByPatient() {
        return ImmutableMap.copyOf(claimsByPatient);
    }

    public ImmutableMap<PayerId, ImmutableList<PayerClaim>> getClaimsByPayer() {
        return ImmutableMap.copyOf(claimsByPayer);
    }

    public record ClaimProcessingInfo(Instant submittedAt, Optional<Instant> responseReceivedAt) {
        private static ClaimProcessingInfo createNew(Instant submittedAt) {
            return new ClaimProcessingInfo(submittedAt, Optional.empty());
        }

        private ClaimProcessingInfo updateOnResponseReceived(Instant responseReceivedAt) {
            return new ClaimProcessingInfo(submittedAt(), Optional.of(responseReceivedAt));
        }
    }

    private void checkClaimExists(String claimId) {
        if (!claims.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim not found: " + claimId);
        }
        if (!processingInfo.containsKey(claimId)) {
            throw new IllegalArgumentException("Claim processing info not found: " + claimId);
        }
    }

    private void checkClaimDoesNotExist(PayerClaim claim) {
        Patient patient = claim.getPatient();
        List<PayerClaim> patientClaims = claimsByPatient.getOrDefault(patient, ImmutableList.of());
        if (patientClaims.stream().anyMatch(c -> c.getClaimId().equals(claim.getClaimId()))) {
            throw new IllegalArgumentException("Claim already exists for patient: " + patient);
        }
        PayerId payerId = claim.getInsurance().getPayerId();
        List<PayerClaim> payerClaims = claimsByPayer.getOrDefault(payerId, ImmutableList.of());
        if (payerClaims.stream().anyMatch(c -> c.getClaimId().equals(claim.getClaimId()))) {
            throw new IllegalArgumentException(
                    "Claim already exists for payer: " + claim.getInsurance().getPayerId());
        }
        ImmutableList<ClaimMap> maps = ImmutableList.of(new ClaimMap(claims, "claims"),
                new ClaimMap(pendingClaims, "pendingClaims"),
                new ClaimMap(processingInfo, "processingInfo"),
                new ClaimMap(remittances, "remittances"),
                new ClaimMap(patientPayments, "patientPayments"));
        for (ClaimMap map : maps) {
            if (map.map().containsKey(claim.getClaimId())) {
                throw new IllegalArgumentException(
                        "Claim already exists: " + claim.getClaimId() + " in " + map.debugName());
            }
        }
    }

    private record ClaimMap(ConcurrentMap<String, ?> map, String debugName) {
    }

    // TODO: Implement persistence
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // TODO: write to disk
    }

    public static ClaimStore newInstanceFromDisk(Path storagePath) {
        throw new UnsupportedOperationException("Not implemented");
    }
}

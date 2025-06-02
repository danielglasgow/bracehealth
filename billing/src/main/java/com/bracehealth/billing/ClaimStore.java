package com.bracehealth.billing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.Patient;
import com.bracehealth.shared.PayerClaim;
import java.util.Optional;
import java.time.Instant;
import java.nio.file.Path;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import java.util.List;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.bracehealth.billing.CurrencyUtil.CurrencyAmount;
import com.bracehealth.billing.PatientStore.PatientId;

/**
 * Stores (in memory) claims that have been submitted.
 * 
 * On application shutdown, the claims are persisted to disk
 * 
 * // TODO: I should just make all the methods in this class synchronized
 */
public class ClaimStore implements ApplicationListener<ContextClosedEvent> {

    private final Path storagePath;
    private final ConcurrentMap<String, PayerClaim> claims;
    private final ConcurrentMap<String, PayerClaim> payerRemittencePendingClaims;
    private final ConcurrentMap<String, PayerClaim> patientPaymentPendingClaims;
    private final ConcurrentMap<String, ClaimProcessingInfo> processingInfo;
    private final ConcurrentMap<String, Remittance> remittances;
    private final ConcurrentMap<String, CurrencyAmount> patientPayments;
    private final ConcurrentMap<PatientId, ImmutableList<PayerClaim>> claimsByPatient;

    public ClaimStore(Path storagePath) {
        this.storagePath = storagePath;
        this.claims = new ConcurrentHashMap<>();
        this.payerRemittencePendingClaims = new ConcurrentHashMap<>();
        this.patientPaymentPendingClaims = new ConcurrentHashMap<>();
        this.processingInfo = new ConcurrentHashMap<>();
        this.remittances = new ConcurrentHashMap<>();
        this.patientPayments = new ConcurrentHashMap<>();
        this.claimsByPatient = new ConcurrentHashMap<>();
    }

    public PayerClaim getClaim(String claimId) {
        return claims.get(claimId);
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
        processingInfo.putIfAbsent(claim.getClaimId(),
                ClaimProcessingInfo.createNew(claim.getClaimId(), submittedAt));
        payerRemittencePendingClaims.putIfAbsent(claim.getClaimId(), claim);
        Patient patient = claim.getPatient();
        PatientId patientId = PatientId.from(patient);
        if (claimsByPatient.containsKey(patientId)) {
            claimsByPatient.computeIfPresent(patientId, (p, claims) -> ImmutableList
                    .<PayerClaim>builder().addAll(claims).add(claim).build());
        } else {
            claimsByPatient.computeIfAbsent(patientId, p -> ImmutableList.of(claim));
        }
    }


    public void addResponse(String claimId, Remittance remittance, Instant responseReceivedAt) {
        checkClaimExists(claimId);
        processingInfo.compute(claimId,
                (id, info) -> info.updateOnResponseReceived(responseReceivedAt));
        remittances.putIfAbsent(claimId, remittance);
        payerRemittencePendingClaims.remove(claimId);
        PayerClaim claim = claims.get(claimId);
        patientPaymentPendingClaims.putIfAbsent(claimId, claim);
    }

    public void addPatientPayment(String claimId, CurrencyAmount amount) {
        checkClaimExists(claimId);
        patientPayments.computeIfPresent(claimId, (id, payment) -> payment.add(amount));
        patientPayments.putIfAbsent(claimId, amount);
    }

    public void markFullyPaid(String claimId, CurrencyAmount amount, Instant closedAt) {
        checkClaimExists(claimId);
        processingInfo.compute(claimId, (id, info) -> info.updateOnClosed(closedAt));
        patientPayments.computeIfPresent(claimId, (id, claim) -> amount);
        patientPayments.putIfAbsent(claimId, amount);
        patientPaymentPendingClaims.remove(claimId);
    }

    public ClaimProcessingInfo getProcessingInfo(String claimId) {
        return processingInfo.get(claimId);
    }

    public Remittance getRemittance(String claimId) {
        return remittances.get(claimId);
    }

    public CurrencyAmount getPatientPayment(String claimId) {
        return patientPayments.getOrDefault(claimId, CurrencyAmount.ZERO);
    }

    public ImmutableMap<String, PayerClaim> getClaims() {
        return ImmutableMap.copyOf(claims);
    }

    public ImmutableList<PayerClaim> getPatientClaims(PatientId patientId) {
        return claimsByPatient.getOrDefault(patientId, ImmutableList.of());
    }

    public ImmutableMap<String, PayerClaim> getPendingClaims() {
        return ImmutableMap.copyOf(payerRemittencePendingClaims);
    }

    public record ClaimProcessingInfo(String claimId, Instant submittedAt,
            Optional<Instant> responseReceivedAt, Optional<Instant> closedAt) {
        private static ClaimProcessingInfo createNew(String claimId, Instant submittedAt) {
            return new ClaimProcessingInfo(claimId, submittedAt, Optional.empty(),
                    Optional.empty());
        }

        private ClaimProcessingInfo updateOnResponseReceived(Instant responseReceivedAt) {
            return new ClaimProcessingInfo(claimId(), submittedAt(),
                    Optional.of(responseReceivedAt), Optional.empty());
        }

        private ClaimProcessingInfo updateOnClosed(Instant closedAt) {
            return new ClaimProcessingInfo(claimId(), submittedAt(), responseReceivedAt(),
                    Optional.of(closedAt));
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
        ImmutableList<ClaimMap> maps = ImmutableList.of(new ClaimMap(claims, "claims"),
                new ClaimMap(payerRemittencePendingClaims, "payerRemittencePendingClaims"),
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

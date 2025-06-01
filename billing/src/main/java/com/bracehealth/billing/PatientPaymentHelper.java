package com.bracehealth.billing;

import com.bracehealth.billing.ClaimStore.ClaimProcessingInfo;
import com.bracehealth.shared.SubmitPatientPaymentResponse.SubmitPatientPaymentResult;
import com.bracehealth.shared.PayerClaim;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse;
import com.bracehealth.shared.GetPatientAccountsReceivableResponse.PatientAccountsReceivableRow;
import com.bracehealth.shared.PatientBalance;
import com.bracehealth.shared.Remittance;
import com.bracehealth.shared.Patient;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Helper for handling patient payments, including updating the patient's balance, and displaying
 * Accounts Receivable across claims.
 */
public class PatientPaymentHelper {
    private static final Logger logger = LoggerFactory.getLogger(PatientPaymentHelper.class);

    private final ClaimStore claimStore;

    public PatientPaymentHelper(ClaimStore claimStore) {
        this.claimStore = claimStore;
    }

    public SubmitPatientPaymentResult payClaim(String claimId, BigDecimal amount) {
        PayerClaim claim = claimStore.getClaim(claimId);
        if (claim == null) {
            logger.error("Claim with ID {} not found", claimId);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_ERROR;
        }
        BigDecimal outstandingBalance = getOutstandingPatientBalance(claimId).total();
        if (outstandingBalance.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("No outstanding balance for claim ID {}", claimId);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE;
        }
        BigDecimal remainingBalance = outstandingBalance.subtract(amount);
        if (remainingBalance.compareTo(BigDecimal.ZERO) > 0) {
            claimStore.addPatientPayment(claimId, amount);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_PAYMENT_APPLIED_BALANCING_OUTSTANDING;
        }
        if (remainingBalance.compareTo(BigDecimal.ZERO) == 0) {
            claimStore.addPatientPayment(claimId, outstandingBalance);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FULLY_PAID;
        }
        if (remainingBalance.compareTo(BigDecimal.ZERO) < 0) {
            claimStore.addPatientPayment(claimId, outstandingBalance);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_AMOUNT_EXCEEDS_OUTSTANDING_BALANCE;
        }
        throw new IllegalStateException("Unreachable");
    }

    public GetPatientAccountsReceivableResponse getPatientAccountsReceivable(
            ImmutableList<Patient> patients) {
        ImmutableMap<Patient, ImmutableList<PayerClaim>> claimsByPatient =
                claimStore.getClaimsByPatient();
        GetPatientAccountsReceivableResponse.Builder responseBuilder =
                GetPatientAccountsReceivableResponse.newBuilder();
        for (Patient patient : patients) {
            ImmutableList<PayerClaim> claims =
                    claimsByPatient.getOrDefault(patient, ImmutableList.of());
            OutstandingBalance cumulativeBalance =
                    new OutstandingBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            List<PayerClaim> outstandingClaims = new ArrayList<>();
            for (PayerClaim claim : claims) {
                OutstandingBalance balance = getOutstandingPatientBalance(claim.getClaimId());
                if (balance.total().compareTo(BigDecimal.ZERO) > 0) {
                    cumulativeBalance = cumulativeBalance.add(balance);
                    outstandingClaims.add(claim);
                }
            }
            PatientBalance balance = PatientBalance.newBuilder()
                    .setOutstandingCopay(CurrencyUtil.toProto(cumulativeBalance.copay))
                    .setOutstandingCoinsurance(CurrencyUtil.toProto(cumulativeBalance.coinsurance))
                    .setOutstandingDeductible(CurrencyUtil.toProto(cumulativeBalance.deductible))
                    .build();
            responseBuilder
                    .addRow(PatientAccountsReceivableRow.newBuilder().setPatient(patient)
                            .setBalance(balance).addAllClaimId(outstandingClaims.stream()
                                    .map(PayerClaim::getClaimId).collect(Collectors.toList()))
                            .build());
        }
        return responseBuilder.build();
    }


    private record OutstandingBalance(BigDecimal copay, BigDecimal coinsurance,
            BigDecimal deductible) {

        BigDecimal total() {
            return copay.add(coinsurance).add(deductible);
        }

        OutstandingBalance add(OutstandingBalance other) {
            return new OutstandingBalance(copay.add(other.copay),
                    coinsurance.add(other.coinsurance), deductible.add(other.deductible));
        }

    }


    private OutstandingBalance getOutstandingPatientBalance(String claimId) {
        ClaimProcessingInfo processingInfo = claimStore.getProcessingInfo(claimId);
        if (processingInfo.responseReceivedAt().isEmpty()) {
            return new OutstandingBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Remittance remittance = claimStore.getRemittance(claimId);
        if (remittance == null) {
            throw new IllegalStateException("Remittance not found for claim ID: " + claimId);
        }
        BigDecimal patientPayment = claimStore.getPatientPayment(claimId);
        BigDecimal[] result = deductInOrder(patientPayment,
                new BigDecimal[] {CurrencyUtil.fromProto(remittance.getCopayAmount()),
                        CurrencyUtil.fromProto(remittance.getCoinsuranceAmount()),
                        CurrencyUtil.fromProto(remittance.getDeductibleAmount())});
        BigDecimal copay = result[0];
        BigDecimal coinsurance = result[1];
        BigDecimal deductible = result[2];
        return new OutstandingBalance(copay, coinsurance, deductible);
    }

    private static BigDecimal[] deductInOrder(BigDecimal totalDeduction, BigDecimal[] amounts) {
        return deductInOrder(amounts, totalDeduction, 0);
    }

    private static BigDecimal[] deductInOrder(BigDecimal[] amounts, BigDecimal deduction,
            int index) {
        if (index == amounts.length) {
            return amounts;
        }
        BigDecimal amount = amounts[index];
        if (amount.compareTo(deduction) >= 0) {
            amounts[index] = amount.subtract(deduction);
            return deductInOrder(amounts, BigDecimal.ZERO, index + 1);
        }
        amounts[index] = BigDecimal.ZERO;
        return deductInOrder(amounts, deduction.subtract(amount), index + 1);
    }

}

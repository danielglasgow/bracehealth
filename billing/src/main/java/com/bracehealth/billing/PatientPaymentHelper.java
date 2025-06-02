package com.bracehealth.billing;

import com.bracehealth.billing.ClaimStore.ClaimProcessingInfo;
import com.bracehealth.billing.CurrencyUtil.CurrencyAmount;
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

    public SubmitPatientPaymentResult payClaim(String claimId, CurrencyAmount amount) {
        PayerClaim claim = claimStore.getClaim(claimId);
        if (claim == null) {
            logger.error("Claim with ID {} not found", claimId);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_ERROR;
        }
        CurrencyAmount outstandingBalance = getOutstandingPatientBalance(claimId).total();
        if (outstandingBalance.isLessThanOrEqualTo(CurrencyAmount.ZERO)) {
            logger.error("No outstanding balance for claim ID {}", claimId);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_NO_OUTSTANDING_BALANCE;
        }
        CurrencyAmount remainingBalance = outstandingBalance.subtract(amount);
        if (remainingBalance.isGreaterThan(CurrencyAmount.ZERO)) {
            claimStore.addPatientPayment(claimId, amount);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_PAYMENT_APPLIED_BALANCING_OUTSTANDING;
        }
        if (remainingBalance.isEqualTo(CurrencyAmount.ZERO)) {
            claimStore.addPatientPayment(claimId, outstandingBalance);
            return SubmitPatientPaymentResult.SUBMIT_PATIENT_PAYMENT_RESULT_FULLY_PAID;
        }
        if (remainingBalance.isLessThan(CurrencyAmount.ZERO)) {
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
            OutstandingBalance cumulativeBalance = new OutstandingBalance(CurrencyAmount.ZERO,
                    CurrencyAmount.ZERO, CurrencyAmount.ZERO);
            List<PayerClaim> outstandingClaims = new ArrayList<>();
            for (PayerClaim claim : claims) {
                OutstandingBalance balance = getOutstandingPatientBalance(claim.getClaimId());
                if (balance.total().isGreaterThan(CurrencyAmount.ZERO)) {
                    cumulativeBalance = cumulativeBalance.add(balance);
                    outstandingClaims.add(claim);
                }
            }
            PatientBalance balance = PatientBalance.newBuilder()
                    .setOutstandingCopay(cumulativeBalance.copay.toProto())
                    .setOutstandingCoinsurance(cumulativeBalance.coinsurance.toProto())
                    .setOutstandingDeductible(cumulativeBalance.deductible.toProto()).build();
            responseBuilder
                    .addRow(PatientAccountsReceivableRow.newBuilder().setPatient(patient)
                            .setBalance(balance).addAllClaimId(outstandingClaims.stream()
                                    .map(PayerClaim::getClaimId).collect(Collectors.toList()))
                            .build());
        }
        return responseBuilder.build();
    }


    private record OutstandingBalance(CurrencyAmount copay, CurrencyAmount coinsurance,
            CurrencyAmount deductible) {

        CurrencyAmount total() {
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
            return new OutstandingBalance(CurrencyAmount.ZERO, CurrencyAmount.ZERO,
                    CurrencyAmount.ZERO);
        }
        Remittance remittance = claimStore.getRemittance(claimId);
        if (remittance == null) {
            throw new IllegalStateException("Remittance not found for claim ID: " + claimId);
        }
        CurrencyAmount patientPayment = claimStore.getPatientPayment(claimId);
        CurrencyAmount[] result = deductInOrder(patientPayment,
                new CurrencyAmount[] {CurrencyAmount.fromProto(remittance.getCopayAmount()),
                        CurrencyAmount.fromProto(remittance.getCoinsuranceAmount()),
                        CurrencyAmount.fromProto(remittance.getDeductibleAmount())});
        CurrencyAmount copay = result[0];
        CurrencyAmount coinsurance = result[1];
        CurrencyAmount deductible = result[2];
        return new OutstandingBalance(copay, coinsurance, deductible);
    }

    private static CurrencyAmount[] deductInOrder(CurrencyAmount totalDeduction,
            CurrencyAmount[] amounts) {
        return deductInOrder(amounts, totalDeduction, 0);
    }

    private static CurrencyAmount[] deductInOrder(CurrencyAmount[] amounts,
            CurrencyAmount deduction, int index) {
        if (index == amounts.length) {
            return amounts;
        }
        if (amounts[index].isGreaterThanOrEqualTo(deduction)) {
            amounts[index] = amounts[index].subtract(deduction);
            return amounts;
        }
        CurrencyAmount remainingDeduction = deduction.subtract(amounts[index]);
        amounts[index] = CurrencyAmount.ZERO;
        return deductInOrder(amounts, remainingDeduction, index + 1);
    }

}

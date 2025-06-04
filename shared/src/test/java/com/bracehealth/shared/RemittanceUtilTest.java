package com.bracehealth.shared;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;

public class RemittanceUtilTest {

    @Test
    public void testGenerateRandomRemittance() {
        // Create a test claim with some service lines
        PayerClaim.Builder claimBuilder = PayerClaim.newBuilder().setClaimId("TEST-CLAIM-123");

        // Add a few service lines with different charges
        claimBuilder.addServiceLines(ServiceLine.newBuilder()
                .setCharge(CurrencyAmount.from("100.00").toProto()).build());
        claimBuilder.addServiceLines(
                ServiceLine.newBuilder().setCharge(CurrencyAmount.from("50.00").toProto()).build());

        PayerClaim claim = claimBuilder.build();

        // Generate a remittance
        Remittance remittance = RemittanceUtil.generateRandomRemittance(claim);

        // Verify the claim ID matches
        assertEquals("TEST-CLAIM-123", remittance.getClaimId());

        // Verify all amounts are non-negative
        assertTrue(CurrencyAmount.fromProto(remittance.getPayerPaidAmount())
                .isGreaterThanOrEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getCoinsuranceAmount())
                .isGreaterThanOrEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getCopayAmount())
                .isGreaterThanOrEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getDeductibleAmount())
                .isGreaterThanOrEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getNotAllowedAmount())
                .isGreaterThanOrEqualTo(CurrencyAmount.ZERO));

        // Calculate total from service lines
        CurrencyAmount expectedTotal = CurrencyAmount.ZERO;
        for (ServiceLine serviceLine : claim.getServiceLinesList()) {
            expectedTotal = expectedTotal.add(CurrencyAmount.fromProto(serviceLine.getCharge()));
        }

        // Calculate total from remittance
        CurrencyAmount actualTotal = CurrencyAmount.fromProto(remittance.getPayerPaidAmount())
                .add(CurrencyAmount.fromProto(remittance.getCoinsuranceAmount()))
                .add(CurrencyAmount.fromProto(remittance.getCopayAmount()))
                .add(CurrencyAmount.fromProto(remittance.getDeductibleAmount()))
                .add(CurrencyAmount.fromProto(remittance.getNotAllowedAmount()));

        // Verify the total matches
        assertTrue(actualTotal.isEqualTo(expectedTotal), "Total amount should match");
    }

    @Test
    public void testGenerateRandomRemittanceWithZeroAmount() {
        // Create a test claim with zero amount
        PayerClaim claim = PayerClaim.newBuilder().setClaimId("TEST-CLAIM-ZERO")
                .addServiceLines(
                        ServiceLine.newBuilder().setCharge(CurrencyAmount.ZERO.toProto()).build())
                .build();

        // Generate a remittance
        Remittance remittance = RemittanceUtil.generateRandomRemittance(claim);

        // Verify all amounts are zero
        assertTrue(CurrencyAmount.fromProto(remittance.getPayerPaidAmount())
                .isEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getCoinsuranceAmount())
                .isEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getCopayAmount())
                .isEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getDeductibleAmount())
                .isEqualTo(CurrencyAmount.ZERO));
        assertTrue(CurrencyAmount.fromProto(remittance.getNotAllowedAmount())
                .isEqualTo(CurrencyAmount.ZERO));
    }
}

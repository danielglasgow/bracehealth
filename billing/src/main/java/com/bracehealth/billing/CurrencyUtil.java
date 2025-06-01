package com.bracehealth.billing;

import com.bracehealth.shared.CurrencyAmount;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Helper for managing currency amounts.
 * 
 * For now assume everything is USD.
 * 
 * Uses BigDecimal for precision.
 * 
 * Note: Maybe this would be cleaner if we just use CurrencyAmount everywhere. (Implementing our own
 * CurrenceAmount addition and subtraction seems easy enough.)
 */

public class CurrencyUtil {
    private static final int DECIMAL_PLACES = 2;
    private static final BigDecimal SCALE_FACTOR = BigDecimal.TEN.pow(DECIMAL_PLACES);

    /**
     * Convert proto (whole + decimal) → BigDecimal.
     */
    public static BigDecimal fromProto(CurrencyAmount proto) {
        BigDecimal whole = BigDecimal.valueOf(proto.getWholeAmount());
        BigDecimal fraction = BigDecimal.valueOf(proto.getDecimalAmount()).divide(SCALE_FACTOR,
                DECIMAL_PLACES, RoundingMode.UNNECESSARY);
        return whole.add(fraction);
    }

    /**
     * Convert BigDecimal → proto (whole + two-digit decimal). Throws ArithmeticException if there
     * are more than two decimal places.
     */
    public static CurrencyAmount toProto(BigDecimal amount) {
        // Ensure exactly two decimals (e.g. 123.450 → 123.45)
        amount = amount.setScale(DECIMAL_PLACES, RoundingMode.UNNECESSARY);
        // Shift decimal point right by two places e.g. 123.45 × 100 = 12345
        long units = amount.multiply(SCALE_FACTOR).longValueExact();
        // Split into “whole” and “decimal”
        int whole = (int) (units / SCALE_FACTOR.longValue()); // 12345 / 100 = 123
        int decimal = (int) (Math.abs(units % SCALE_FACTOR.longValue())); // 12345 % 100 = 45
        return CurrencyAmount.newBuilder().setWholeAmount(whole).setDecimalAmount(decimal).build();
    }
}

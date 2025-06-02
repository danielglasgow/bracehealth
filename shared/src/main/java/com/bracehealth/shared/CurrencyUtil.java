package com.bracehealth.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Helper for managing currency amounts.
 * 
 * For now assume everything is USD.
 * 
 * // This class is a bit ugly...
 */

public class CurrencyUtil {
    private static final int DECIMAL_PLACES = 2;
    private static final BigDecimal SCALE_FACTOR = BigDecimal.TEN.pow(DECIMAL_PLACES);


    /**
     * Convert proto (whole + decimal) → BigDecimal.
     */
    private static BigDecimal protoToBigDecimal(CurrencyValue proto) {
        BigDecimal whole = BigDecimal.valueOf(proto.getWholeAmount());
        BigDecimal fraction = BigDecimal.valueOf(proto.getDecimalAmount()).divide(SCALE_FACTOR,
                DECIMAL_PLACES, RoundingMode.UNNECESSARY);
        return whole.add(fraction);
    }

    private static BigDecimal standardize(BigDecimal value) {
        return protoToBigDecimal(bigDecimalToProto(value));
    }

    /**
     * Convert BigDecimal → proto (whole + two-digit decimal). Throws ArithmeticException if there
     * are more than two decimal places.
     */
    private static CurrencyValue bigDecimalToProto(BigDecimal amount) {
        // Ensure exactly two decimals (e.g. 123.450 → 123.45)
        amount = amount.setScale(DECIMAL_PLACES, RoundingMode.DOWN);
        // Shift decimal point right by two places e.g. 123.45 × 100 = 12345
        long units = amount.multiply(SCALE_FACTOR).longValueExact();
        return minUnitToProto((int) units);
    }

    private static CurrencyValue minUnitToProto(int units) {
        int whole = (int) (units / SCALE_FACTOR.longValue()); // 12345 / 100 = 123
        int decimal = (int) (Math.abs(units % SCALE_FACTOR.longValue())); // 12345 % 100 = 45
        return CurrencyValue.newBuilder().setWholeAmount(whole).setDecimalAmount(decimal).build();
    }

    public record CurrencyAmount(BigDecimal value) {
        public CurrencyAmount(BigDecimal value) {
            this.value = standardize(value);
        }

        public static final CurrencyAmount ZERO = new CurrencyAmount(BigDecimal.ZERO);
        public static final CurrencyAmount ONE = new CurrencyAmount(BigDecimal.ONE);

        public CurrencyValue toProto() {
            return bigDecimalToProto(value);
        }

        public CurrencyAmount add(CurrencyAmount other) {
            BigDecimal newValue = value.add(other.value);
            return new CurrencyAmount(newValue);
        }

        public CurrencyAmount subtract(CurrencyAmount other) {
            BigDecimal newValue = value.subtract(other.value);
            return new CurrencyAmount(newValue);
        }

        public CurrencyAmount addDecimal(int minUnits) {
            return add(CurrencyAmount.fromProto(minUnitToProto(minUnits)));
        }

        public CurrencyAmount subtractDecimal(int minUnits) {
            return subtract(CurrencyAmount.fromProto(minUnitToProto(minUnits)));
        }

        public SubtractUntilZeroResult subtractUntilZero(CurrencyAmount other) {
            return other.isGreaterThan(this)
                    ? new SubtractUntilZeroResult(CurrencyAmount.ZERO, other.subtract(this))
                    : new SubtractUntilZeroResult(this.subtract(other), CurrencyAmount.ZERO);
        }

        public boolean isEqualTo(CurrencyAmount other) {
            return value.compareTo(other.value) == 0;
        }

        public boolean isGreaterThan(CurrencyAmount other) {
            return value.compareTo(other.value) > 0;
        }

        public boolean isGreaterThanOrEqualTo(CurrencyAmount other) {
            return value.compareTo(other.value) >= 0;
        }

        public boolean isLessThan(CurrencyAmount other) {
            return value.compareTo(other.value) < 0;
        }

        public boolean isLessThanOrEqualTo(CurrencyAmount other) {
            return value.compareTo(other.value) <= 0;
        }

        public static CurrencyAmount fromProto(CurrencyValue proto) {
            return new CurrencyAmount(protoToBigDecimal(proto));
        }

        public static CurrencyAmount from(BigDecimal amount) {
            return CurrencyAmount.fromProto(bigDecimalToProto(amount));
        }

        public static CurrencyAmount from(String amount) {
            return CurrencyAmount.from(new BigDecimal(amount));
        }
    }

    public record SubtractUntilZeroResult(CurrencyAmount value, CurrencyAmount remaining) {
    }
}

package com.bracehealth.billing;

import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyUtilTest {

    @Test
    void from_withDifferentDecimalPlaces_roundsToTwoPlaces() {
        // Test various ways to represent 100.00
        CurrencyAmount amount1 = CurrencyAmount.from("100.00");
        CurrencyAmount amount2 = CurrencyAmount.from("100");
        CurrencyAmount amount3 = CurrencyAmount.from("100.0");
        CurrencyAmount amount4 = CurrencyAmount.from("100.0000");

        assertEquals(amount1, amount2, "100.00 should equal 100");
        assertEquals(amount1, amount3, "100.00 should equal 100.0");
        assertEquals(amount1, amount4, "100.00 should equal 100.0000");
    }

    @Test
    void from_withMoreThanTwoDecimals_roundsDown() {
        CurrencyAmount amount1 = CurrencyAmount.from("100.999");
        CurrencyAmount amount2 = CurrencyAmount.from("100.991");
        assertEquals(CurrencyAmount.from("100.99"), amount1, "100.999 should round down to 100.99");
        assertEquals(CurrencyAmount.from("100.99"), amount2, "100.991 should round down to 100.99");
    }


    @Test
    void from_withNegativeAmounts_roundsCorrectly() {
        CurrencyAmount amount1 = CurrencyAmount.from("-100.999");
        CurrencyAmount amount2 = CurrencyAmount.from("-100.991");

        assertEquals(CurrencyAmount.from("-100.99"), amount1,
                "-100.999 should round down to -100.99");
        assertEquals(CurrencyAmount.from("-100.99"), amount2,
                "-100.991 should round down to -100.99");
    }

    @Test
    void from_withSmallAmounts_roundsCorrectly() {
        CurrencyAmount amount1 = CurrencyAmount.from("0.001");
        CurrencyAmount amount2 = CurrencyAmount.from("0.009");

        assertEquals(CurrencyAmount.from("0.00"), amount1, "0.001 should round down to 0.00");
        assertEquals(CurrencyAmount.from("0.00"), amount2, "0.009 should round down to 0.00");
    }

    @Test
    void from_withLargeAmounts_roundsCorrectly() {
        CurrencyAmount amount1 = CurrencyAmount.from("999999.999");
        CurrencyAmount amount2 = CurrencyAmount.from("999999.991");

        assertEquals(CurrencyAmount.from("999999.99"), amount1,
                "999999.999 should round down to 999999.99");
        assertEquals(CurrencyAmount.from("999999.99"), amount2,
                "999999.991 should round down to 999999.99");
    }
}

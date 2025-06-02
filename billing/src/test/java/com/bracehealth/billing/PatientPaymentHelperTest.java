package com.bracehealth.billing;

import com.bracehealth.shared.CurrencyUtil.CurrencyAmount;
import com.bracehealth.billing.PatientPaymentHelper.OutstandingBalance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatientPaymentHelperTest {

    @Test
    void subtractUntilZero_withZeroAmount_returnsSameBalance() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.ZERO);

        assertEquals(balance, result);
    }

    @Test
    void subtractUntilZero_withNegativeAmount_returnsSameBalance() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("-50.00"));

        assertEquals(balance, result);
    }

    @Test
    void subtractUntilZero_withAmountLessThanCopay_subtractsFromCopayOnly() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("50.00"));

        assertEquals(CurrencyAmount.from("50.00"), result.copay());
        assertEquals(CurrencyAmount.from("200.00"), result.coinsurance());
        assertEquals(CurrencyAmount.from("300.00"), result.deductible());
    }

    @Test
    void subtractUntilZero_withAmountEqualToCopay_zerosCopayOnly() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("100.00"));

        assertEquals(CurrencyAmount.ZERO, result.copay());
        assertEquals(CurrencyAmount.from("200.00"), result.coinsurance());
        assertEquals(CurrencyAmount.from("300.00"), result.deductible());
    }

    @Test
    void subtractUntilZero_withAmountGreaterThanCopay_subtractsFromCopayAndCoinsurance() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("150.00"));

        assertEquals(CurrencyAmount.ZERO, result.copay());
        assertEquals(CurrencyAmount.from("150.00"), result.coinsurance());
        assertEquals(CurrencyAmount.from("300.00"), result.deductible());
    }

    @Test
    void subtractUntilZero_withAmountEqualToTotal_zerosAllComponents() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("600.00"));

        assertEquals(CurrencyAmount.ZERO, result.copay());
        assertEquals(CurrencyAmount.ZERO, result.coinsurance());
        assertEquals(CurrencyAmount.ZERO, result.deductible());
    }

    @Test
    void subtractUntilZero_withAmountGreaterThanTotal_zerosAllComponents() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.from("100.00"),
                CurrencyAmount.from("200.00"), CurrencyAmount.from("300.00"));

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("1000.00"));

        assertEquals(CurrencyAmount.ZERO, result.copay());
        assertEquals(CurrencyAmount.ZERO, result.coinsurance());
        assertEquals(CurrencyAmount.ZERO, result.deductible());
    }

    @Test
    void subtractUntilZero_withZeroBalance_returnsZeroBalance() {
        OutstandingBalance balance = new OutstandingBalance(CurrencyAmount.ZERO,
                CurrencyAmount.ZERO, CurrencyAmount.ZERO);

        OutstandingBalance result = balance.subtractUntilZero(CurrencyAmount.from("100.00"));

        assertEquals(CurrencyAmount.ZERO, result.copay());
        assertEquals(CurrencyAmount.ZERO, result.coinsurance());
        assertEquals(CurrencyAmount.ZERO, result.deductible());
    }
}

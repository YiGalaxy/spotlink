package com.spotlink.trading;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.trading.service.TradingNumbers;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TradingNumbersTest {
    @Test void roundsTheAgreedAmountOnceAtFourDecimalPlaces() {
        assertThat(TradingNumbers.amount(new BigDecimal("1.001"), new BigDecimal("68000.1234")))
                .isEqualByComparingTo("68068.1235");
        assertThat(TradingNumbers.amount(new BigDecimal("0.001"), new BigDecimal("0.05")))
                .isEqualByComparingTo("0.0001");
        assertThat(TradingNumbers.amount(BigDecimal.ONE, new BigDecimal("999999999999999.9999")))
                .isEqualByComparingTo("999999999999999.9999");
    }
    @Test void rejectsOverflowZeroRoundedAmountAndUnrepresentableInputs() {
        for (String[] pair : new String[][] {{"2", "999999999999999.9999"}, {"0.001", "0.0001"},
                {"1.0001", "1"}, {"1", "1.00001"}, {"0", "1"}}) {
            assertThatThrownBy(() -> TradingNumbers.amount(new BigDecimal(pair[0]), new BigDecimal(pair[1])))
                    .isInstanceOf(BusinessException.class);
        }
    }
}

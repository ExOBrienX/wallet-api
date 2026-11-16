package cl.exobrienx.wallet.account;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGeneratorTest {

    private final AccountNumberGenerator generator = new AccountNumberGenerator();

    @RepeatedTest(50)
    void generatesTenDigitNumbersWithValidCheckDigit() {
        String number = generator.generate();

        assertThat(number).matches("[1-9]\\d{9}");
        assertThat(AccountNumberGenerator.isValid(number)).isTrue();
    }

    @Test
    void computesLuhnCheckDigit() {
        assertThat(AccountNumberGenerator.luhnCheckDigit("799273987")).isEqualTo(5);
        assertThat(AccountNumberGenerator.luhnCheckDigit("123456789")).isEqualTo(7);
        assertThat(AccountNumberGenerator.isValid("7992739875")).isTrue();
        assertThat(AccountNumberGenerator.isValid("1234567897")).isTrue();
        assertThat(AccountNumberGenerator.isValid("0000000000")).isTrue();
    }

    @Test
    void detectsSingleDigitTypo() {
        String number = generator.generate();
        char changed = number.charAt(4) == '9' ? '0' : (char) (number.charAt(4) + 1);
        String typo = number.substring(0, 4) + changed + number.substring(5);

        assertThat(AccountNumberGenerator.isValid(typo)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "12345678901", "12345abcde", ""})
    void rejectsMalformedNumbers(String input) {
        assertThat(AccountNumberGenerator.isValid(input)).isFalse();
    }
}

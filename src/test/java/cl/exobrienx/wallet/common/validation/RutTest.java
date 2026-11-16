package cl.exobrienx.wallet.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RutTest {

    @ParameterizedTest
    @CsvSource({
            "12.345.678-5, 12345678-5",
            "12345678-5,   12345678-5",
            "123456785,    12345678-5",
            "' 12345678-5 ', 12345678-5",
            "5.126.663-3,  5126663-3",
            "11.111.111-1, 11111111-1",
            "10.000.013-k, 10000013-K",
            "15.000.013-0, 15000013-0"
    })
    void normalizesValidRuts(String input, String expected) {
        assertThat(Rut.normalize(input)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12.345.678-9",  // dígito verificador incorrecto
            "12345678-K",    // dígito verificador incorrecto
            "1234-5",        // demasiado corto
            "123.456.789-0", // demasiado largo
            "12.345.678",    // sin dígito verificador
            "02345678-5",    // cero inicial
            "12.34.5678-5",  // puntos mal ubicados
            "abc",
            ""
    })
    void rejectsInvalidRuts(String input) {
        assertThat(Rut.isValid(input)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"12345678, 5", "10000013, K", "15000013, 0", "5126663, 3"})
    void computesCheckDigitWithModulo11(String body, char expected) {
        assertThat(Rut.computeCheckDigit(body)).isEqualTo(expected);
    }
}

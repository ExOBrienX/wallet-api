package cl.exobrienx.wallet.account;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Genera números de cuenta de 10 dígitos: 9 aleatorios más un dígito verificador calculado
 * con el algoritmo de Luhn (el mismo de las tarjetas de crédito). El dígito verificador
 * permite detectar errores de tipeo, como un dígito cambiado o dos dígitos invertidos,
 * antes de buscar la cuenta.
 */
@Component
public class AccountNumberGenerator {

    private static final int PAYLOAD_DIGITS = 9;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder payload = new StringBuilder(PAYLOAD_DIGITS);
        payload.append(1 + random.nextInt(9)); // sin cero inicial, para no confundirlo con la cuenta del sistema
        for (int i = 1; i < PAYLOAD_DIGITS; i++) {
            payload.append(random.nextInt(10));
        }
        return payload.append(luhnCheckDigit(payload)).toString();
    }

    public static boolean isValid(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("\\d{10}")) {
            return false;
        }
        String payload = accountNumber.substring(0, PAYLOAD_DIGITS);
        return luhnCheckDigit(payload) == Character.digit(accountNumber.charAt(PAYLOAD_DIGITS), 10);
    }

    /**
     * Luhn: desde la derecha, se duplica un dígito sí y uno no (empezando por el primero);
     * si el doble supera 9 se le resta 9. El dígito verificador es lo que falta para que la
     * suma total sea múltiplo de 10.
     */
    static int luhnCheckDigit(CharSequence payload) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int digit = Character.digit(payload.charAt(i), 10);
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }
}

package cl.exobrienx.wallet.support;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Generadores de datos únicos y válidos para los tests. */
public final class TestData {

    private TestData() {
    }

    public static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@wallet.cl";
    }

    /** RUT aleatorio de 8 dígitos con dígito verificador correcto. */
    public static String randomRut() {
        String body = String.valueOf(ThreadLocalRandom.current().nextInt(10_000_000, 30_000_000));
        int sum = 0;
        int factor = 2;
        for (int i = body.length() - 1; i >= 0; i--) {
            sum += Character.digit(body.charAt(i), 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int result = 11 - (sum % 11);
        char checkDigit = result == 11 ? '0' : result == 10 ? 'K' : (char) ('0' + result);
        return body + "-" + checkDigit;
    }

    /** Número de 10 dígitos sin validar dígito verificador (para inserts directos por SQL). */
    public static String randomAccountNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
    }

    public static String registerJson(String email, String password, String rut) {
        return """
                {"email": "%s", "password": "%s", "firstName": "Ana", "lastName": "Pérez Soto",
                 "rut": "%s", "phone": "+56911112222"}""".formatted(email, password, rut);
    }
}

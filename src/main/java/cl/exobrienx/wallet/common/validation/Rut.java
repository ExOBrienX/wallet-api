package cl.exobrienx.wallet.common.validation;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidades para el RUT chileno (Rol Único Tributario).
 * <p>
 * Acepta las formas habituales de escribirlo ("12.345.678-5", "12345678-5", "123456785")
 * y lo normaliza a cuerpo sin puntos, guion y dígito verificador en mayúscula: "12345678-5".
 */
public final class Rut {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{1,2}(?:\\.?\\d{3}){2})-?([\\dkK])$");

    private Rut() {
    }

    /**
     * Normaliza un RUT si su formato y dígito verificador son válidos.
     *
     * @return el RUT normalizado, o vacío si no es válido
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(input.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String body = matcher.group(1).replace(".", "");
        char checkDigit = Character.toUpperCase(matcher.group(2).charAt(0));
        if (body.startsWith("0") || body.length() < 7 || computeCheckDigit(body) != checkDigit) {
            return Optional.empty();
        }
        return Optional.of(body + "-" + checkDigit);
    }

    public static boolean isValid(String input) {
        return normalize(input).isPresent();
    }

    /**
     * Calcula el dígito verificador con el algoritmo módulo 11: se multiplican los dígitos,
     * de derecha a izquierda, por la serie 2, 3, 4, 5, 6, 7 (repitiéndola), se suman y se
     * toma 11 menos el resto de dividir por 11. El resultado 11 es "0" y el 10 es "K".
     */
    static char computeCheckDigit(String body) {
        int sum = 0;
        int factor = 2;
        for (int i = body.length() - 1; i >= 0; i--) {
            sum += Character.digit(body.charAt(i), 10) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }
        int result = 11 - (sum % 11);
        return switch (result) {
            case 11 -> '0';
            case 10 -> 'K';
            default -> (char) ('0' + result);
        };
    }
}

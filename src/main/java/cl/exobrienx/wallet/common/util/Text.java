package cl.exobrienx.wallet.common.util;

/** Utilidades para textos opcionales que llegan desde la API. */
public final class Text {

    private Text() {
    }

    /** Recorta espacios y convierte un texto vacío en {@code null}, para no guardar cadenas en blanco. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

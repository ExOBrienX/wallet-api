package cl.exobrienx.wallet.user;

/**
 * Datos para crear un usuario, ya validados pero aún sin normalizar.
 * Lo usan el registro público, el administrador inicial y los datos de demostración.
 *
 * @param rut   puede ser nulo solo para administradores
 * @param phone opcional
 */
public record NewUser(
        String email,
        String rawPassword,
        String firstName,
        String lastName,
        String rut,
        String phone,
        Role role) {
}

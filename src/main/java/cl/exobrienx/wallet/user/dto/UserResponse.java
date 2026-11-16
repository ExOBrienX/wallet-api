package cl.exobrienx.wallet.user.dto;

import cl.exobrienx.wallet.user.Role;
import cl.exobrienx.wallet.user.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String rut,
        String phone,
        Role role,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getRut(), user.getPhone(), user.getRole(), user.getCreatedAt());
    }
}

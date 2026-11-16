package cl.exobrienx.wallet.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Página de resultados con un formato JSON propio y estable. Se evita serializar el {@code Page}
 * de Spring Data, cuyo formato expone detalles internos y puede cambiar entre versiones.
 *
 * @param page número de página, desde 0
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}

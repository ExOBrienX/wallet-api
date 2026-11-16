package cl.exobrienx.wallet.common.config;

import org.apache.catalina.Pipeline;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;

/**
 * Algunas peticiones las rechaza Tomcat antes de que lleguen a Spring (por ejemplo, una URL con
 * una barra invertida codificada, {@code %5C}). Por defecto Tomcat responde con una página HTML;
 * aquí se reemplaza por una respuesta en formato ProblemDetail, igual que el resto de la API.
 * <p>
 * Solo actúa cuando nadie escribió todavía la respuesta: los errores que maneja Spring ya llegan
 * con su cuerpo y no se tocan.
 */
@Component
public class TomcatErrorConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                // Tomcat agrega al arrancar la válvula configurada aquí si no encuentra una de esa clase.
                host.setErrorReportValveClass(ProblemDetailErrorReportValve.class.getName());
                Pipeline pipeline = host.getPipeline();
                for (Valve valve : pipeline.getValves()) {
                    if (valve instanceof ErrorReportValve) {
                        pipeline.removeValve(valve);
                    }
                }
                pipeline.addValve(new ProblemDetailErrorReportValve());
            }
        });
    }

    /** Después del personalizador de Spring Boot, que agrega su propia válvula de errores. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Página de error de Tomcat reemplazada por un JSON en formato ProblemDetail, sin datos del servidor. */
    public static class ProblemDetailErrorReportValve extends ErrorReportValve {

        public ProblemDetailErrorReportValve() {
            setShowReport(false);
            setShowServerInfo(false);
        }

        @Override
        protected void report(Request request, Response response, Throwable throwable) {
            int status = response.getStatus();
            if (status < 400 || response.getContentWritten() > 0) {
                return;
            }
            boolean badRequest = status == HttpStatus.BAD_REQUEST.value();
            String code = badRequest ? "REQUEST_REJECTED" : codeOf(status);
            String title = badRequest ? "Petición rechazada" : "Error en la petición";
            String detail = badRequest
                    ? "La petición no es válida o la URL contiene caracteres no permitidos."
                    : "El servidor no pudo procesar la petición.";
            String json = """
                    {"type":"about:blank","title":"%s","status":%d,"code":"%s","detail":"%s"}"""
                    .formatted(title, status, code, detail);
            try {
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                Writer writer = response.getReporter();
                if (writer != null) {
                    writer.write(json);
                    response.finishResponse();
                }
            } catch (IOException | IllegalStateException e) {
                // La conexión ya no admite escritura: no hay nada más que hacer.
            }
        }

        private static String codeOf(int status) {
            HttpStatus resolved = HttpStatus.resolve(status);
            return resolved != null ? resolved.name() : "HTTP_" + status;
        }
    }
}

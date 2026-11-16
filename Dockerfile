# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Etapa 1: compilación. Los tests no se ejecutan aquí: corren en CI con
# Testcontainers, que necesita acceso a Docker.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Primero solo lo necesario para descargar dependencias: mientras el pom.xml no
# cambie, Docker reutiliza esta capa y no las vuelve a descargar.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/wallet.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------
# Etapa 2: imagen final, solo con el JRE y la aplicación.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system wallet && useradd --system --gid wallet --no-create-home wallet

# Capas ordenadas de la que menos cambia a la que más: las dependencias quedan
# en caché y un cambio en el código solo reconstruye la última capa.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
COPY --chmod=755 docker-entrypoint.sh docker-healthcheck.sh /usr/local/bin/

USER wallet
EXPOSE 8080

# El contenedor se considera sano cuando /actuator/health/readiness responde UP.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD ["docker-healthcheck.sh"]

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["docker-entrypoint.sh"]

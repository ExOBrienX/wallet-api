#!/bin/bash
# Healthcheck del contenedor: la API está lista cuando /actuator/health/readiness responde UP
# (base de datos y libro contable disponibles).
#
# La imagen no incluye curl ni wget, y agregarlos solo para esto aumentaría su tamaño y su
# superficie de ataque. Bash puede abrir una conexión TCP con /dev/tcp y hacer la petición HTTP.
set -e

exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
grep -q '"status":"UP"' <&3

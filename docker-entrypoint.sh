#!/bin/sh
set -e

# Si no se definió JWT_SECRET, se genera uno aleatorio para esta ejecución. Así la imagen
# arranca sin configuración previa y el repositorio no contiene ningún secreto. Como el
# secreto cambia en cada arranque, los tokens emitidos dejan de valer al reiniciar.
if [ -z "$JWT_SECRET" ]; then
    JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')
    export JWT_SECRET
    echo "JWT_SECRET no está definido: se generó uno aleatorio para esta ejecución." >&2
    echo "Para mantener las sesiones entre reinicios, defínelo en el archivo .env." >&2
fi

exec java $JAVA_OPTS -jar /app/wallet.jar "$@"

-- =============================================================================
-- V2: datos de perfil, número de cuenta legible y detalle de transacciones.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Perfil de usuario
-- -----------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN first_name VARCHAR(60),
    ADD COLUMN last_name  VARCHAR(60),
    -- RUT normalizado: cuerpo sin puntos, guion y dígito verificador en mayúscula (12345678-5).
    ADD COLUMN rut        VARCHAR(10),
    -- Celular chileno en formato E.164 (+569XXXXXXXX). Opcional.
    ADD COLUMN phone      VARCHAR(12),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Usuarios creados antes de esta versión: el nombre se toma del correo para poder
-- exigir NOT NULL. Los clientes sin RUT no tienen forma de completarse aquí, así que la
-- restricción del RUT se valida solo para filas nuevas (NOT VALID).
UPDATE users
   SET first_name = split_part(email, '@', 1),
       last_name  = ''
 WHERE first_name IS NULL;

ALTER TABLE users
    ALTER COLUMN first_name SET NOT NULL,
    ALTER COLUMN last_name SET NOT NULL,
    ADD CONSTRAINT uq_users_rut UNIQUE (rut),
    ADD CONSTRAINT ck_users_rut_format CHECK (rut ~ '^[1-9][0-9]{6,7}-[0-9K]$'),
    ADD CONSTRAINT ck_users_phone_format CHECK (phone ~ '^\+569[0-9]{8}$'),
    -- Todo cliente (USER) tiene RUT; el personal interno (ADMIN) no lo necesita.
    ADD CONSTRAINT ck_users_rut_required CHECK (role = 'ADMIN' OR rut IS NOT NULL) NOT VALID;

-- -----------------------------------------------------------------------------
-- Cuentas: número legible y alias
-- -----------------------------------------------------------------------------
ALTER TABLE accounts
    -- 10 dígitos: 9 aleatorios + dígito verificador (algoritmo de Luhn).
    ADD COLUMN account_number CHAR(10),
    -- Nombre que el usuario le da a la cuenta ("Ahorro", "Gastos"). Opcional.
    ADD COLUMN alias          VARCHAR(40),
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE accounts SET account_number = '0000000000'
 WHERE id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE accounts
    ALTER COLUMN account_number SET NOT NULL,
    ADD CONSTRAINT uq_accounts_number UNIQUE (account_number),
    ADD CONSTRAINT ck_accounts_number_format CHECK (account_number ~ '^[0-9]{10}$');

-- -----------------------------------------------------------------------------
-- Transacciones: glosa y fecha de término
-- -----------------------------------------------------------------------------
ALTER TABLE transactions
    -- Glosa opcional ingresada por el usuario ("Pago arriendo").
    ADD COLUMN description  VARCHAR(140),
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_transactions_completed_at CHECK (
        status NOT IN ('COMPLETED', 'REVERSED') OR completed_at IS NOT NULL
    );

-- Historial por usuario iniciador (consultas administrativas y límite diario).
CREATE INDEX ix_transactions_initiated_by_created ON transactions (initiated_by, created_at);

-- La regla de inmutabilidad ahora cubre la glosa, y permite fijar completed_at una sola
-- vez (de NULL a un valor), cuando la transacción termina.
CREATE OR REPLACE FUNCTION forbid_transaction_changes() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'transactions no se puede borrar'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.type, NEW.idempotency_key, NEW.initiated_by,
        NEW.reverses_transaction_id, NEW.description, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.type, OLD.idempotency_key, OLD.initiated_by,
        OLD.reverses_transaction_id, OLD.description, OLD.created_at) THEN
        RAISE EXCEPTION 'transactions: solo se puede modificar el status'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.completed_at IS NOT NULL AND NEW.completed_at IS DISTINCT FROM OLD.completed_at THEN
        RAISE EXCEPTION 'transactions: completed_at no se puede modificar una vez fijado'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

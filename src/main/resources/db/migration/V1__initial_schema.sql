-- =============================================================================
-- V1: esquema inicial de la billetera.
-- Crea todas las tablas del modelo de datos, la cuenta interna SYSTEM y las
-- protecciones a nivel de base de datos para las reglas contables.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Usuarios y autenticación
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id),
    -- SHA-256 en hexadecimal del token; el token en claro nunca se guarda.
    token_hash CHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- -----------------------------------------------------------------------------
-- Cuentas
-- -----------------------------------------------------------------------------
CREATE TABLE accounts (
    id         UUID        PRIMARY KEY,
    user_id    UUID        REFERENCES users (id),
    currency   CHAR(3)     NOT NULL,
    status     VARCHAR(10) NOT NULL,
    type       VARCHAR(10) NOT NULL,
    -- Bloqueo optimista (@Version) para cambios a la cuenta en sí (ej. status).
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'CLP'),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED')),
    CONSTRAINT ck_accounts_type CHECK (type IN ('USER', 'SYSTEM')),
    -- Las cuentas de usuario tienen dueño; las del sistema no.
    CONSTRAINT ck_accounts_owner CHECK (
        (type = 'USER' AND user_id IS NOT NULL) OR (type = 'SYSTEM' AND user_id IS NULL)
    )
);

CREATE INDEX ix_accounts_user ON accounts (user_id);

-- -----------------------------------------------------------------------------
-- Libro contable
-- -----------------------------------------------------------------------------
CREATE TABLE transactions (
    id                      UUID         PRIMARY KEY,
    type                    VARCHAR(12)  NOT NULL,
    status                  VARCHAR(10)  NOT NULL,
    -- Nulo solo en reversas (las hace un ADMIN y no pasan por Idempotency-Key).
    idempotency_key         VARCHAR(100),
    initiated_by            UUID         NOT NULL REFERENCES users (id),
    reverses_transaction_id UUID         REFERENCES transactions (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT ck_transactions_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    -- La clave de idempotencia es única por usuario: dos usuarios pueden usar la misma.
    CONSTRAINT uq_transactions_idempotency UNIQUE (initiated_by, idempotency_key),
    -- Una transacción se revierte a lo más una vez.
    CONSTRAINT uq_transactions_reverses UNIQUE (reverses_transaction_id),
    -- Solo las reversas apuntan a otra transacción, y siempre lo hacen.
    CONSTRAINT ck_transactions_reversal CHECK (
        (type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL)
    ),
    -- Toda operación que no es reversa trae clave de idempotencia.
    CONSTRAINT ck_transactions_idempotency CHECK (
        type = 'REVERSAL' OR idempotency_key IS NOT NULL
    )
);

CREATE TABLE ledger_entries (
    id             UUID        PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions (id),
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    -- Pesos enteros, siempre positivos; el signo lo da direction.
    amount         BIGINT      NOT NULL,
    direction      VARCHAR(6)  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

-- Saldo e historial por cuenta (y filtro por fechas).
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at);
CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);

-- -----------------------------------------------------------------------------
-- Idempotencia
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    user_id         UUID         NOT NULL REFERENCES users (id),
    key             VARCHAR(100) NOT NULL,
    -- SHA-256 del cuerpo de la petición, para detectar "misma clave, cuerpo distinto".
    request_hash    CHAR(64)     NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_records PRIMARY KEY (user_id, key)
);

-- -----------------------------------------------------------------------------
-- Protecciones contables a nivel de base de datos (defensa en profundidad:
-- la aplicación ya cumple estas reglas, pero la base no confía ciegamente en ella).
-- -----------------------------------------------------------------------------

-- Regla 3: los asientos son inmutables.
CREATE FUNCTION forbid_ledger_entry_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries es inmutable: no se permite %', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_entry_changes();

-- Regla 3: de una transacción solo puede cambiar el status, y nunca se borra.
CREATE FUNCTION forbid_transaction_changes() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'transactions no se puede borrar'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.type, NEW.idempotency_key, NEW.initiated_by,
        NEW.reverses_transaction_id, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.type, OLD.idempotency_key, OLD.initiated_by,
        OLD.reverses_transaction_id, OLD.created_at) THEN
        RAISE EXCEPTION 'transactions: solo se puede modificar el status'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_transactions_immutable
    BEFORE UPDATE OR DELETE ON transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_transaction_changes();

-- Regla 1: los asientos de cada transacción cuadran (débitos = créditos).
-- Es un trigger diferido: se evalúa al hacer COMMIT, cuando ya se insertaron
-- todos los asientos de la transacción, y no después de cada INSERT.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    net     BIGINT;
    entries INTEGER;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0),
           COUNT(*)
      INTO net, entries
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF entries < 2 OR net <> 0 THEN
        RAISE EXCEPTION 'La transacción % no cuadra (asientos: %, neto: %)',
            NEW.transaction_id, entries, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER tg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

-- -----------------------------------------------------------------------------
-- Cuenta interna SYSTEM ("cuenta externa"): contraparte de depósitos y retiros.
-- Su saldo es negativo por diseño: refleja el dinero que entró al sistema.
-- -----------------------------------------------------------------------------
INSERT INTO accounts (id, user_id, currency, status, type)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'CLP', 'ACTIVE', 'SYSTEM');

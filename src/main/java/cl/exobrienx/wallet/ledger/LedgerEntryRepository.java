package cl.exobrienx.wallet.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** Saldo de una cuenta: créditos menos débitos. Cero si no tiene movimientos. */
    @Query("""
            select coalesce(sum(case when e.direction = cl.exobrienx.wallet.ledger.EntryDirection.CREDIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e
            where e.account.id = :accountId
            """)
    long balanceOf(UUID accountId);

    /**
     * Saldos de varias cuentas en una sola consulta, para no hacer una consulta por cuenta
     * al listar (el problema N+1). Las cuentas sin movimientos no aparecen en el resultado.
     */
    @Query("""
            select new cl.exobrienx.wallet.ledger.AccountBalance(e.account.id,
                   sum(case when e.direction = cl.exobrienx.wallet.ledger.EntryDirection.CREDIT
                            then e.amount else -e.amount end))
            from LedgerEntry e
            where e.account.id in :accountIds
            group by e.account.id
            """)
    List<AccountBalance> balancesOf(Collection<UUID> accountIds);

    /**
     * Total que una cuenta envió por transferencias desde {@code since}. Cuenta también las
     * transferencias revertidas después: el límite mide lo que se intentó mover en el día.
     */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from LedgerEntry e
            where e.account.id = :accountId
              and e.direction = cl.exobrienx.wallet.ledger.EntryDirection.DEBIT
              and e.transaction.type = cl.exobrienx.wallet.ledger.TransactionType.TRANSFER
              and e.createdAt >= :since
            """)
    long transferredSince(UUID accountId, Instant since);

    /**
     * Movimientos de una cuenta entre {@code from} (incluido) y {@code to} (excluido), del más
     * reciente al más antiguo. La contraparte se obtiene con una subconsulta sobre el otro asiento
     * de la misma transacción, considerando solo cuentas de clientes.
     */
    @Query(value = """
            select new cl.exobrienx.wallet.ledger.AccountMovement(
                       t.id, t.type, t.status, e.direction, e.amount, t.description,
                       (select min(oa.accountNumber)
                        from LedgerEntry o join o.account oa
                        where o.transaction = t and oa.id <> :accountId
                          and oa.type = cl.exobrienx.wallet.account.AccountType.USER),
                       e.createdAt)
            from LedgerEntry e join e.transaction t
            where e.account.id = :accountId and e.createdAt >= :from and e.createdAt < :to
            order by e.createdAt desc, e.id desc
            """,
            countQuery = """
            select count(e) from LedgerEntry e
            where e.account.id = :accountId and e.createdAt >= :from and e.createdAt < :to
            """)
    Page<AccountMovement> movementsOf(UUID accountId, Instant from, Instant to, Pageable pageable);
}

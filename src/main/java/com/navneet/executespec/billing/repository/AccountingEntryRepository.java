package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.AccountingEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, UUID>,
        JpaSpecificationExecutor<AccountingEntry> {

    List<AccountingEntry> findByJournalRef(String journalRef);

    List<AccountingEntry> findByInvoiceId(UUID invoiceId);

    Page<AccountingEntry> findByEntryDateBetweenOrderByEntryDateDesc(
            LocalDate from, LocalDate to, Pageable pageable);

    @Query("SELECT e.account, e.entryType, SUM(e.amount) FROM AccountingEntry e " +
           "WHERE e.entryDate BETWEEN :from AND :to " +
           "GROUP BY e.account, e.entryType " +
           "ORDER BY e.account")
    List<Object[]> getTrialBalance(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT SUM(e.amount) FROM AccountingEntry e " +
           "WHERE e.account = :account AND e.entryType = :entryType " +
           "AND e.entryDate BETWEEN :from AND :to")
    Long sumByAccountAndType(@Param("account") String account,
                             @Param("entryType") String entryType,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);
}

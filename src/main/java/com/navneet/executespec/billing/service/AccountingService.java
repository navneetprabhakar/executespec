package com.navneet.executespec.billing.service;

import com.navneet.executespec.billing.entity.AccountingEntry;
import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.repository.AccountingEntryRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingService {

    private final AccountingEntryRepository entryRepo;
    private final InvoiceRepository invoiceRepo;

    // ── Record entries on invoice events ─────────────────────────────────────

    @Transactional
    public void recordRevenueEntry(Invoice invoice) {
        String journalRef = "INV-" + invoice.getInvoiceNumber();
        long total = invoice.getTotalAmount();
        long tax = invoice.getTotalTax();
        long revenue = invoice.getSubtotal();

        List<AccountingEntry> entries = new ArrayList<>();

        // DR Bank (total inclusive of tax)
        entries.add(buildEntry(journalRef, "BANK", "DEBIT", total, invoice));

        // CR Revenue (subtotal before tax)
        entries.add(buildEntry(journalRef, "REVENUE", "CREDIT", revenue, invoice));

        // CR GST Output (tax portion)
        if (tax > 0) {
            entries.add(buildEntry(journalRef, "GST_OUTPUT", "CREDIT", tax, invoice));
        }

        entryRepo.saveAll(entries);
        log.info("Recorded revenue entries: journalRef={}, total={}", journalRef, total);
    }

    @Transactional
    public void recordRefundEntry(Invoice creditNote) {
        String journalRef = "CN-" + creditNote.getInvoiceNumber();
        long total = creditNote.getTotalAmount();
        long tax = creditNote.getTotalTax();
        long revenue = creditNote.getSubtotal();

        List<AccountingEntry> entries = new ArrayList<>();

        // DR Revenue (reverse the revenue)
        entries.add(buildEntry(journalRef, "REVENUE", "DEBIT", revenue, creditNote));

        // DR GST Output (reverse the tax)
        if (tax > 0) {
            entries.add(buildEntry(journalRef, "GST_OUTPUT", "DEBIT", tax, creditNote));
        }

        // CR Bank (refund out)
        entries.add(buildEntry(journalRef, "BANK", "CREDIT", total, creditNote));

        entryRepo.saveAll(entries);
        log.info("Recorded refund entries: journalRef={}, total={}", journalRef, total);
    }

    // ── Revenue metrics ──────────────────────────────────────────────────────

    public record RevenueMetrics(
            long totalCollections,
            long totalRevenue,
            long totalTax,
            long mrr,
            long arr
    ) {}

    @Transactional(readOnly = true)
    public RevenueMetrics getRevenueMetrics(LocalDate from, LocalDate to) {
        Long collections = entryRepo.sumByAccountAndType("BANK", "DEBIT", from, to);
        Long revenue = entryRepo.sumByAccountAndType("REVENUE", "CREDIT", from, to);
        Long tax = entryRepo.sumByAccountAndType("GST_OUTPUT", "CREDIT", from, to);

        long c = collections != null ? collections : 0;
        long r = revenue != null ? revenue : 0;
        long t = tax != null ? tax : 0;

        // MRR = subscription revenue this month (approximation)
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
        Long monthlyRevenue = entryRepo.sumByAccountAndType("REVENUE", "CREDIT", monthStart, monthEnd);
        long mrr = monthlyRevenue != null ? monthlyRevenue : 0;

        return new RevenueMetrics(c, r, t, mrr, mrr * 12);
    }

    // ── GST summary ──────────────────────────────────────────────────────────

    public record GstSummary(
            long cgstCollected,
            long sgstCollected,
            long igstCollected,
            long totalGst,
            String financialYear,
            int quarter
    ) {}

    @Transactional(readOnly = true)
    public GstSummary getGstSummary(String financialYear, int quarter) {
        LocalDate[] range = quarterRange(financialYear, quarter);

        // Sum from invoice records for accurate GST breakdown
        long cgst = 0, sgst = 0, igst = 0;
        var invoices = invoiceRepo.findAll(); // For now, filter in memory
        for (Invoice inv : invoices) {
            if (!"PAID".equals(inv.getStatus()) && !"ISSUED".equals(inv.getStatus())) continue;
            if (inv.getInvoiceDate() == null) continue;
            if (inv.getInvoiceDate().isBefore(range[0]) || inv.getInvoiceDate().isAfter(range[1])) continue;

            cgst += inv.getCgstAmount();
            sgst += inv.getSgstAmount();
            igst += inv.getIgstAmount();
        }

        return new GstSummary(cgst, sgst, igst, cgst + sgst + igst, financialYear, quarter);
    }

    // ── GSTR-1 export ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] exportGstr1(String financialYear, int quarter) {
        LocalDate[] range = quarterRange(financialYear, quarter);

        var invoices = invoiceRepo.findAll();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(baos);

        // CSV header
        pw.println("Invoice Number,Invoice Date,Invoice Type,Buyer GSTIN,Buyer Name," +
                "Place of Supply,Subtotal,CGST Rate,CGST Amount,SGST Rate,SGST Amount," +
                "IGST Rate,IGST Amount,Total Tax,Total Amount,SAC Code,Reverse Charge,Export");

        for (Invoice inv : invoices) {
            if (!"PAID".equals(inv.getStatus()) && !"ISSUED".equals(inv.getStatus())) continue;
            if (inv.getInvoiceDate() == null) continue;
            if (inv.getInvoiceDate().isBefore(range[0]) || inv.getInvoiceDate().isAfter(range[1])) continue;

            pw.printf("%s,%s,%s,%s,\"%s\",%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%s,%s%n",
                    inv.getInvoiceNumber(),
                    inv.getInvoiceDate(),
                    inv.getInvoiceType(),
                    nvl(inv.getBuyerGstin()),
                    nvl(inv.getBuyerName()),
                    nvl(inv.getPlaceOfSupply()),
                    inv.getSubtotal() / 100.0,
                    inv.getCgstRate() != null ? inv.getCgstRate() : 0,
                    inv.getCgstAmount() / 100.0,
                    inv.getSgstRate() != null ? inv.getSgstRate() : 0,
                    inv.getSgstAmount() / 100.0,
                    inv.getIgstRate() != null ? inv.getIgstRate() : 0,
                    inv.getIgstAmount() / 100.0,
                    inv.getTotalTax() / 100.0,
                    inv.getTotalAmount() / 100.0,
                    nvl(inv.getSacCode()),
                    inv.isReverseCharge() ? "Y" : "N",
                    inv.isExport_() ? "Y" : "N");
        }
        pw.flush();
        return baos.toByteArray();
    }

    // ── Trial balance ────────────────────────────────────────────────────────

    public record TrialBalanceEntry(String account, long debitTotal, long creditTotal) {}

    @Transactional(readOnly = true)
    public List<TrialBalanceEntry> getTrialBalance(LocalDate from, LocalDate to) {
        List<Object[]> rows = entryRepo.getTrialBalance(from, to);
        Map<String, long[]> balances = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String account = (String) row[0];
            String entryType = (String) row[1];
            long amount = ((Number) row[2]).longValue();

            balances.computeIfAbsent(account, k -> new long[2]);
            if ("DEBIT".equals(entryType)) {
                balances.get(account)[0] += amount;
            } else {
                balances.get(account)[1] += amount;
            }
        }

        return balances.entrySet().stream()
                .map(e -> new TrialBalanceEntry(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    // ── Entries listing ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AccountingEntry> getEntries(LocalDate from, LocalDate to, Pageable pageable) {
        return entryRepo.findByEntryDateBetweenOrderByEntryDateDesc(from, to, pageable);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AccountingEntry buildEntry(String journalRef, String account, String entryType,
                                        long amount, Invoice invoice) {
        return AccountingEntry.builder()
                .journalRef(journalRef)
                .account(account)
                .entryType(entryType)
                .amount(amount)
                .invoiceId(invoice.getId())
                .description(invoice.getDescription())
                .entryDate(invoice.getInvoiceDate())
                .periodStart(invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().withDayOfMonth(1) : null)
                .periodEnd(invoice.getInvoiceDate())
                .build();
    }

    /** Returns [startDate, endDate] for the given FY quarter (1-4). */
    private LocalDate[] quarterRange(String fy, int quarter) {
        // FY format: "2526" means Apr 2025 - Mar 2026
        int startYear = 2000 + Integer.parseInt(fy.substring(0, 2));
        return switch (quarter) {
            case 1 -> new LocalDate[]{
                    LocalDate.of(startYear, Month.APRIL, 1),
                    LocalDate.of(startYear, Month.JUNE, 30)
            };
            case 2 -> new LocalDate[]{
                    LocalDate.of(startYear, Month.JULY, 1),
                    LocalDate.of(startYear, Month.SEPTEMBER, 30)
            };
            case 3 -> new LocalDate[]{
                    LocalDate.of(startYear, Month.OCTOBER, 1),
                    LocalDate.of(startYear, Month.DECEMBER, 31)
            };
            case 4 -> new LocalDate[]{
                    LocalDate.of(startYear + 1, Month.JANUARY, 1),
                    LocalDate.of(startYear + 1, Month.MARCH, 31)
            };
            default -> throw new IllegalArgumentException("Quarter must be 1-4");
        };
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }
}

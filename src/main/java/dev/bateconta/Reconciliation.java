package dev.bateconta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import static dev.bateconta.CsvImport.Source.PAYOUTS;
import static dev.bateconta.CsvImport.Source.SALES;

@Service
class Reconciliation {
    enum Status { MATCHED, AMOUNT_MISMATCH, MISSING_PAYOUT, UNEXPECTED_PAYOUT, DUPLICATE }

    record Sale(long grossCents, long feeCents, long netCents) {}

    record Item(String id, Status status, Long expectedCents, Long actualCents, Long differenceCents,
                List<Sale> sales, List<Long> payouts) {}

    record Report(int saleCount, int payoutCount, Map<Status, Integer> counts, List<Item> items) {}

    Report reconcile(byte[] salesCsv, byte[] payoutsCsv) {
        var sales = CsvImport.read(salesCsv, SALES);
        var payouts = CsvImport.read(payoutsCsv, PAYOUTS);
        var salesById = new LinkedHashMap<String, List<Sale>>();
        for (var sale : sales) {
            salesById.computeIfAbsent(sale.id(), key -> new ArrayList<>()).add(
                    new Sale(sale.amountCents(), sale.feeCents(), sale.amountCents() - sale.feeCents()));
        }

        var payoutsById = new LinkedHashMap<String, List<Long>>();
        for (var payout : payouts) {
            payoutsById.computeIfAbsent(payout.id(), key -> new ArrayList<>()).add(payout.amountCents());
        }

        var transactionIds = new LinkedHashSet<>(salesById.keySet());
        transactionIds.addAll(payoutsById.keySet());
        var counts = new EnumMap<Status, Integer>(Status.class);
        for (Status status : Status.values()) {
            counts.put(status, 0);
        }

        var items = new ArrayList<Item>(transactionIds.size());
        for (String id : transactionIds) {
            Item item = match(id, salesById.getOrDefault(id, List.of()), payoutsById.getOrDefault(id, List.of()));
            items.add(item);
            counts.merge(item.status(), 1, Integer::sum);
        }
        return new Report(sales.size(), payouts.size(), counts, items);
    }

    private Item match(String id, List<Sale> sales, List<Long> payouts) {
        Long expected = sales.size() == 1 ? sales.getFirst().netCents() : null;
        Long actual = payouts.size() == 1 ? payouts.getFirst() : null;
        if (sales.size() > 1 || payouts.size() > 1) {
            return new Item(id, Status.DUPLICATE, expected, actual, null, sales, payouts);
        }
        if (expected == null) {
            return new Item(id, Status.UNEXPECTED_PAYOUT, null, actual, null, sales, payouts);
        }
        if (actual == null) {
            return new Item(id, Status.MISSING_PAYOUT, expected, null, null, sales, payouts);
        }

        long difference = actual - expected;
        Status status = difference == 0 ? Status.MATCHED : Status.AMOUNT_MISMATCH;
        return new Item(id, status, expected, actual, difference, sales, payouts);
    }
}

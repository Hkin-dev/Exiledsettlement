package com.hkin.exiledsettlement.network;

import java.util.*;

/** Bounded view of current public stock and today's actual warehouse movements. */
public final class CommunityLedgerData {
    public static final String MARKER = "community_daily_stock_v2";
    public static final int PAGE_SIZE = 12;
    public record Row(String item, long stock, long outgoing, long incoming) {
        public long change() { return incoming - outgoing; }
    }
    public record Page(int index, int pages, long day, boolean partial, int legacyStores, List<Row> rows) { }
    private CommunityLedgerData() { }

    public static List<String> encode(Map<String,Long> stock, Map<String,Long> outgoing, Map<String,Long> incoming,
                                      long day, boolean partial, int legacyStores, int requestedPage) {
        var ids = new TreeSet<String>(stock.keySet()); ids.addAll(outgoing.keySet()); ids.addAll(incoming.keySet());
        var rows = new ArrayList<Row>();
        for (var id : ids) {
            long amount = Math.max(0, stock.getOrDefault(id, 0L)), out = Math.max(0, outgoing.getOrDefault(id, 0L)), in = Math.max(0, incoming.getOrDefault(id, 0L));
            if (amount > 0 || in > 0 || out > 0) rows.add(new Row(id, amount, out, in));
        }
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        var lines = new ArrayList<String>();
        lines.add(MARKER);
        lines.add(page + "|" + pages + "|" + day + "|" + (partial ? 1 : 0) + "|" + legacyStores);
        for (int i = page * PAGE_SIZE; i < Math.min(rows.size(), (page + 1) * PAGE_SIZE); i++) {
            Row row = rows.get(i);
            lines.add(row.item() + "|" + row.stock() + "|" + row.outgoing() + "|" + row.incoming());
        }
        return List.copyOf(lines);
    }

    public static Page decode(List<String> lines) {
        if (lines.size() < 2 || lines.size() > PAGE_SIZE + 2 || !MARKER.equals(lines.getFirst()))
            throw new IllegalArgumentException("Invalid ledger view");
        var header = lines.get(1).split("\\|", -1);
        if (header.length != 5) throw new IllegalArgumentException("Invalid ledger header");
        int page = Integer.parseInt(header[0]), pages = Integer.parseInt(header[1]);
        long day = Long.parseLong(header[2]); int partial = Integer.parseInt(header[3]), legacy = Integer.parseInt(header[4]);
        if (page < 0 || pages < 1 || page >= pages || partial < 0 || partial > 1 || legacy < 0) throw new IllegalArgumentException("Invalid ledger page");
        var rows = new ArrayList<Row>();
        for (int i = 2; i < lines.size(); i++) {
            var values = lines.get(i).split("\\|", -1);
            if (values.length != 4 || !values[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid ledger row");
            long stock = Long.parseLong(values[1]), out = Long.parseLong(values[2]), in = Long.parseLong(values[3]);
            if (stock < 0 || in < 0 || out < 0) throw new IllegalArgumentException("Negative ledger count");
            rows.add(new Row(values[0], stock, out, in));
        }
        return new Page(page, pages, day, partial == 1, legacy, List.copyOf(rows));
    }
}

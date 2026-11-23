package dev.bateconta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

final class CsvImport {
    private static final int MAX_ROWS = 5_000;
    private static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
    private static final Pattern MONEY = Pattern.compile("[0-9]{1,9}(?:[.,][0-9]{1,2})?");

    enum Source {
        SALES("Vendas", List.of("id_transacao", "valor_bruto", "taxa")),
        PAYOUTS("Repasses", List.of("id_transacao", "valor_repassado"));

        final String label;
        final List<String> columns;

        Source(String label, List<String> columns) {
            this.label = label;
            this.columns = columns;
        }
    }

    record Entry(String id, long amountCents, long feeCents) {}

    static List<Entry> read(byte[] bytes, Source source) {
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw invalid(source, "salve o CSV em UTF-8.");
        }

        String text = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        char delimiter = firstLine.contains(";") ? ';' : ',';
        var format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setTrim(true)
                .setIgnoreSurroundingSpaces(true).get();

        try (CSVParser parser = CSVParser.parse(text, format)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw invalid(source, "o arquivo precisa de um cabeçalho.");
            }

            List<String> header = records.next().toList();
            if (header.size() != source.columns.size() || !new HashSet<>(header).containsAll(source.columns)) {
                throw invalid(source, "use as colunas " + String.join(", ", source.columns) + ".");
            }

            int idColumn = header.indexOf("id_transacao");
            String amountName = source.columns.get(1);
            int amountColumn = header.indexOf(amountName);
            int feeColumn = header.indexOf("taxa");
            var entries = new ArrayList<Entry>();

            while (records.hasNext()) {
                if (entries.size() >= MAX_ROWS) {
                    throw invalid(source, "o limite é de 5.000 transações por arquivo.");
                }
                var record = records.next();
                String position = "registro " + record.getRecordNumber() + ": ";
                if (record.size() != header.size()) {
                    throw invalid(source, position + "quantidade de colunas diferente do cabeçalho.");
                }
                String id = record.get(idColumn);
                if (!TRANSACTION_ID.matcher(id).matches()) {
                    throw invalid(source, position + "id_transacao inválido.");
                }
                long amount = cents(record.get(amountColumn), source, position, amountName);
                long fee = feeColumn < 0 ? 0 : cents(record.get(feeColumn), source, position, "taxa");
                if (source == Source.SALES && (amount == 0 || fee > amount)) {
                    throw invalid(source, position + "o valor bruto deve ser positivo e cobrir a taxa.");
                }
                entries.add(new Entry(id, amount, fee));
            }
            return entries;
        } catch (IOException | UncheckedIOException error) {
            throw invalid(source, "formato CSV inválido. Confira separadores e aspas.");
        }
    }

    private static long cents(String value, Source source, String position, String column) {
        if (!MONEY.matcher(value).matches()) {
            throw invalid(source, position + column + " deve ser um valor sem separador de milhar e com até duas casas decimais.");
        }
        return new BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact();
    }

    private static InvalidCsv invalid(Source source, String detail) {
        return new InvalidCsv(source.label + " — " + detail);
    }

    static final class InvalidCsv extends RuntimeException {
        InvalidCsv(String message) {
            super(message);
        }
    }
}

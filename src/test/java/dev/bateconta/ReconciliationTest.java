package dev.bateconta;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static dev.bateconta.Reconciliation.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationTest {
    private final Reconciliation reconciliation = new Reconciliation();

    @Test
    void exampleCoversAllFiveSituationsAndExplainsTheDifference() throws Exception {
        var report = reconciliation.reconcile(
                new ClassPathResource("static/exemplos/vendas.csv").getContentAsByteArray(),
                new ClassPathResource("static/exemplos/repasses.csv").getContentAsByteArray());

        assertThat(report.saleCount()).isEqualTo(7);
        assertThat(report.payoutCount()).isEqualTo(8);
        assertThat(report.counts()).containsEntry(MATCHED, 4).containsEntry(AMOUNT_MISMATCH, 1)
                .containsEntry(MISSING_PAYOUT, 1).containsEntry(UNEXPECTED_PAYOUT, 1).containsEntry(DUPLICATE, 1);
        assertThat(report.items().getFirst()).satisfies(item -> {
            assertThat(item.id()).isEqualTo("BC-1051");
            assertThat(item.expectedCents()).isEqualTo(9700L);
            assertThat(item.actualCents()).isEqualTo(9500L);
            assertThat(item.differenceCents()).isEqualTo(-200L);
        });
    }

    @Test
    void handlesQuotedDecimalCommaBomAndReorderedColumns() {
        var report = reconciliation.reconcile(
                csv("\uFEFFtaxa,id_transacao,valor_bruto\r\n\"0,01\",BC-7,\"0,30\"\r\n"),
                csv("id_transacao;valor_repassado\nBC-7;0,29\n"));
        assertThat(report.items().getFirst()).satisfies(item -> {
            assertThat(item.status()).isEqualTo(MATCHED);
            assertThat(item.expectedCents()).isEqualTo(29L);
            assertThat(item.differenceCents()).isZero();
        });
    }

    @Test
    void duplicateSalePreservesOriginalLinesWithoutMatchingAmounts() {
        var report = reconciliation.reconcile(
                csv("id_transacao,valor_bruto,taxa\nA,100,3\nA,90,3\n"),
                csv("id_transacao,valor_repassado\nA,97\n"));
        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(DUPLICATE);
            assertThat(item.sales()).hasSize(2);
            assertThat(item.payouts()).containsExactly(9700L);
            assertThat(item.differenceCents()).isNull();
        });
    }

    @Test
    void emptyPayoutReportFlagsMissingPayment() {
        var report = reconciliation.reconcile(csv("id_transacao,valor_bruto,taxa\nA,19.90,0.50\n"),
                csv("id_transacao,valor_repassado\n"));
        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(MISSING_PAYOUT);
            assertThat(item.expectedCents()).isEqualTo(1940L);
            assertThat(item.actualCents()).isNull();
        });
    }

    @Test
    void rejectsUnexpectedHeadersAndFeesHigherThanGross() {
        var payouts = csv("id_transacao,valor_repassado\n");
        assertThatThrownBy(() -> reconciliation.reconcile(csv("id,total,taxa\nA,1,2\n"), payouts))
                .isInstanceOf(CsvImport.InvalidCsv.class).hasMessageContaining("use as colunas");
        assertThatThrownBy(() -> reconciliation.reconcile(csv("id_transacao,valor_bruto,taxa\nA,1,2\n"), payouts))
                .isInstanceOf(CsvImport.InvalidCsv.class).hasMessageContaining("registro 2");
    }

    @Test
    void rejectsMalformedCsvAndOverLimit() {
        var payouts = csv("id_transacao,valor_repassado\n");
        assertThatThrownBy(() -> reconciliation.reconcile(csv("id_transacao,valor_bruto,taxa\n\"unclosed"), payouts))
                .isInstanceOf(CsvImport.InvalidCsv.class).hasMessageContaining("formato CSV inválido");
        assertThatThrownBy(() -> reconciliation.reconcile(csv("id_transacao,valor_bruto,taxa\n" +
                "A,5,1\n".repeat(5001)), payouts))
                .isInstanceOf(CsvImport.InvalidCsv.class).hasMessageContaining("5.000");
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

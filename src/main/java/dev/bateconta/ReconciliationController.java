package dev.bateconta;

import java.io.IOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
class ReconciliationController {
    private final Reconciliation reconciliation;

    ReconciliationController(Reconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/api/exemplo")
    Reconciliation.Report example() throws IOException {
        return reconciliation.reconcile(
                new ClassPathResource("static/exemplos/vendas.csv").getContentAsByteArray(),
                new ClassPathResource("static/exemplos/repasses.csv").getContentAsByteArray());
    }

    @PostMapping(path = "/api/conciliacoes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Reconciliation.Report> reconcile(@RequestPart("vendas") MultipartFile sales,
                                                      @RequestPart("repasses") MultipartFile payouts) throws IOException {
        var report = reconciliation.reconcile(sales.getBytes(), payouts.getBytes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
    }
}

@RestControllerAdvice
class ImportErrors {
    @ExceptionHandler(CsvImport.InvalidCsv.class)
    ProblemDetail invalidCsv(CsvImport.InvalidCsv error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail largerFile() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Cada arquivo pode ter até 1 MB.");
    }
}

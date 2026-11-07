package eu.tenderintel.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceDataTest {

    @Test
    void cpvLevelFollowsTheCodeStructure() {
        assertThat(CpvImportService.level("45000000")).isEqualTo(1);
        assertThat(CpvImportService.level("45200000")).isEqualTo(2);
        assertThat(CpvImportService.level("45210000")).isEqualTo(3);
        assertThat(CpvImportService.level("45213000")).isEqualTo(4);
        assertThat(CpvImportService.level("45213100")).isEqualTo(5);
        assertThat(CpvImportService.level("45213150")).isEqualTo(5);
        assertThat(CpvImportService.level("03000000")).isEqualTo(1);
    }

    @Test
    void parsesEcbEurofxrefFormat() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                                 xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                  <gesmes:subject>Reference rates</gesmes:subject>
                  <Cube>
                    <Cube time='2026-11-06'>
                      <Cube currency='PLN' rate='4.3925'/>
                      <Cube currency='CZK' rate='24.350'/>
                    </Cube>
                    <Cube time='2026-11-05'>
                      <Cube currency='PLN' rate='4.4010'/>
                    </Cube>
                  </Cube>
                </gesmes:Envelope>
                """;
        List<EcbRatesImportService.Rate> rates = EcbRatesImportService.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(rates).containsExactly(
                new EcbRatesImportService.Rate("PLN", LocalDate.of(2026, 11, 6), new BigDecimal("4.3925")),
                new EcbRatesImportService.Rate("CZK", LocalDate.of(2026, 11, 6), new BigDecimal("24.350")),
                new EcbRatesImportService.Rate("PLN", LocalDate.of(2026, 11, 5), new BigDecimal("4.4010")));
    }

    @Test
    void rejectsXmlWithDoctypeToPreventXxe() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Cube><Cube time='2026-11-06'><Cube currency='PLN' rate='&xxe;'/></Cube></Cube>
                """;
        assertThatThrownBy(() -> EcbRatesImportService.parse(xxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }
}

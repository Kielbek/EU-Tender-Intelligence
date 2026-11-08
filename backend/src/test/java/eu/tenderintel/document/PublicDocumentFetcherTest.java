package eu.tenderintel.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PublicDocumentFetcherTest {

    @Test
    void nonPublicAddressesAreRecognised() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1"}) {
            assertThat(PublicDocumentFetcher.isNonPublic(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        assertThat(PublicDocumentFetcher.isNonPublic(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void resolverRefusesNamesPointingInside() {
        // The connection uses only what this resolver returns, so a rebinding answer cannot be used later.
        assertThatThrownBy(() -> PublicDocumentFetcher.SAFE_DNS.lookup("localhost"))
                .isInstanceOf(PublicDocumentFetcher.PrivateAddressException.class);
    }

    @Test
    void literalPrivateAddressesAndBadLinksAreRefusedWithoutAConnection() {
        PublicDocumentFetcher f = new PublicDocumentFetcher();
        assertThat(f.fetch("http://127.0.0.1:9/a.pdf", 1000).detail()).isEqualTo("PRIVATE_NETWORK");
        assertThat(f.fetch("http://[::1]/a.pdf", 1000).detail()).isEqualTo("PRIVATE_NETWORK");
        assertThat(f.fetch("ftp://example.com/a.pdf", 1000).detail()).isEqualTo("INVALID_LINK");
        assertThat(f.fetch("http://localhost:8080/a.pdf", 1000).detail()).isEqualTo("PRIVATE_NETWORK");
    }
}

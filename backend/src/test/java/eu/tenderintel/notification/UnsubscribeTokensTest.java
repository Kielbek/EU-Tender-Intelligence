package eu.tenderintel.notification;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.config.AppProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UnsubscribeTokensTest {

    static UnsubscribeTokens tokens(String secret) {
        AppProperties props = Mockito.mock(AppProperties.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(props.security().jwtSecret()).thenReturn(secret);
        return new UnsubscribeTokens(props);
    }

    @Test
    void tokensRoundTripAndCannotBeForged() {
        UnsubscribeTokens t = tokens("dGVzdC1vbmx5LWtleS1kby1ub3QtdXNlLWluLXByb2QhIQ==");
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        String token = t.create(user, org);
        assertThat(t.verify(token)).containsExactly(user, org);

        // Another user's id with the original signature.
        String[] parts = token.split("\\.");
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((UUID.randomUUID() + "." + org).getBytes());
        assertThat(t.verify(forgedBody + "." + parts[1])).isNull();
        // Signed with another server's key.
        assertThat(tokens("b3RoZXIta2V5LW90aGVyLWtleS1vdGhlci1rZXktMTIzNA==").verify(token)).isNull();
        assertThat(t.verify("garbage")).isNull();
        assertThat(t.verify(null)).isNull();
    }
}

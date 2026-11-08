package eu.tenderintel.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import eu.tenderintel.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Claude via the official Anthropic Java SDK. Streaming is used because analyses have long inputs and outputs. */
@Component
class AnthropicClaudeGateway implements ClaudeGateway {

    /** Server-side fallback when a safety classifier declines a request (routes by refusal category). */
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AppProperties.Ai props;
    private final AnthropicClient client;

    AnthropicClaudeGateway(AppProperties props) {
        this.props = props.ai();
        this.client = this.props.configured()
                ? AnthropicOkHttpClient.builder().apiKey(this.props.anthropicApiKey())
                        .timeout(this.props.requestTimeout()).maxRetries(2).build()
                : null;
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public boolean configured() {
        return client != null;
    }

    @Override
    public Response analyze(String system, List<DocumentInput> documents, String instruction) {
        requireConfigured();
        List<ContentBlockParam> blocks = new ArrayList<>();
        CitationsConfigParam citations = CitationsConfigParam.builder().enabled(true).build();
        for (DocumentInput d : documents) {
            DocumentBlockParam.Builder b = DocumentBlockParam.builder().title(d.title()).citations(citations);
            if (d.kind() == Kind.PDF) {
                b.source(Base64PdfSource.builder().data(d.data()).build());
            } else {
                b.source(PlainTextSource.builder().data(d.data()).build());
            }
            blocks.add(ContentBlockParam.ofDocument(b.build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(instruction).build()));
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(props.analysisModel())
                .maxTokens(props.analysisMaxOutputTokens())
                .system(system)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.analysisEffort())).build())
                .addUserMessageOfContentBlockParams(blocks);
        if (props.refusalFallback()) {
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        MessageCreateParams params = builder.build();
        MessageAccumulator acc = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            stream.stream().forEach(acc::accumulate);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new GatewayException("ai_auth_failed", "The Anthropic API key was rejected", e);
        } catch (RateLimitException e) {
            throw new GatewayException("ai_rate_limited", "Anthropic rate limit reached", e);
        } catch (NotFoundException e) {
            throw new GatewayException("ai_model_unavailable", "Model not available: " + props.analysisModel(), e);
        } catch (AnthropicServiceException e) {
            throw new GatewayException("ai_error", "Anthropic API error " + e.statusCode(), e);
        } catch (RuntimeException e) {
            throw new GatewayException("ai_error", "Could not complete the request to Anthropic", e);
        }
        return toResponse(acc.message());
    }

    static Response toResponse(Message m) {
        List<Segment> segments = new ArrayList<>();
        for (ContentBlock block : m.content()) {
            block.text().ifPresent(t -> {
                List<Citation> cites = new ArrayList<>();
                for (TextCitation c : t.citations().orElse(List.of())) {
                    c.pageLocation().ifPresent(p -> cites.add(new Citation((int) p.documentIndex(), p.citedText(),
                            (int) p.startPageNumber(), (int) p.endPageNumber(), null, null)));
                    c.charLocation().ifPresent(p -> cites.add(new Citation((int) p.documentIndex(), p.citedText(),
                            null, null, (int) p.startCharIndex(), (int) p.endCharIndex())));
                }
                segments.add(new Segment(t.text(), cites));
            });
        }
        var u = m.usage();
        String stop = m.stopReason().map(Object::toString).orElse(null);
        return new Response(m.model().toString(), stop, segments, new Usage(u.inputTokens(), u.outputTokens(),
                u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L)));
    }

    @Override
    public String check() {
        requireConfigured();
        try {
            return client.models().retrieve(props.analysisModel()).id();
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new GatewayException("ai_auth_failed", "The Anthropic API key was rejected", e);
        } catch (NotFoundException e) {
            throw new GatewayException("ai_model_unavailable", "Model not available: " + props.analysisModel(), e);
        } catch (RuntimeException e) {
            throw new GatewayException("ai_error", "Could not reach the Anthropic API", e);
        }
    }

    private void requireConfigured() {
        if (client == null) {
            throw new GatewayException("ai_not_configured", "ANTHROPIC_API_KEY is not set", null);
        }
    }
}

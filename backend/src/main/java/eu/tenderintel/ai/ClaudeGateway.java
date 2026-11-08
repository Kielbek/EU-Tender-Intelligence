package eu.tenderintel.ai;

import java.util.List;

/**
 * The single place where the platform talks to the model. Production: {@link AnthropicClaudeGateway}; tests replace it
 * with a fake that returns responses of the same shape (text segments with citations).
 */
public interface ClaudeGateway {

    boolean configured();

    /** Sends documents (with citations enabled) plus an instruction; returns the answer as cited text segments. */
    Response analyze(String system, List<DocumentInput> documents, String instruction);

    /** Cheap call that proves the API key and the model are usable (admin "check connection"). */
    String check();

    enum Kind { PDF, TEXT }

    /** One document block. {@code data} is base64 for PDF, plain text for TEXT. */
    record DocumentInput(String title, Kind kind, String data) {
    }

    /** A citation returned by the API – {@code citedText} is extracted by the API from the document, not generated. */
    record Citation(int documentIndex, String citedText, Integer startPage, Integer endPageExclusive,
            Integer startChar, Integer endChar) {
    }

    record Segment(String text, List<Citation> citations) {
    }

    record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
    }

    record Response(String model, String stopReason, List<Segment> segments, Usage usage) {
    }

    /** A failure with a stable code for the UI ({@code ai_auth_failed}, {@code ai_rate_limited}, …). */
    class GatewayException extends RuntimeException {
        private final String code;

        public GatewayException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

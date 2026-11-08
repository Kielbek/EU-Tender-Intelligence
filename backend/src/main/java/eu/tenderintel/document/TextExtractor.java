package eu.tenderintel.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.Tika;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * Detects document types from content (never trusting the file name alone), unpacks ZIP archives safely and extracts
 * text with Apache Tika. PDFs are sent to Claude as PDFs (it reads layout and tables); for them we only check that a
 * text layer exists, because scanned pages without text cannot be cited.
 */
@Component
public class TextExtractor {

    public static final String PDF = "application/pdf";
    static final String ZIP = "application/zip";
    /** Formats we accept: PDF plus office/text formats converted to plain text. */
    static final Set<String> SUPPORTED = Set.of(PDF,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.oasis.opendocument.spreadsheet");
    static final int MAX_TEXT_CHARS = 2_000_000;
    static final int MAX_ZIP_ENTRIES = 60;
    static final long MAX_ZIP_TOTAL_BYTES = 100L * 1024 * 1024;

    private final Tika tika = new Tika();

    public record Extraction(String status, Integer pageCount, String text, String error) {
    }

    public record Entry(String fileName, byte[] content) {
    }

    public String detect(byte[] content, String fileName) {
        String type = tika.detect(content, fileName);
        int semi = type.indexOf(';');
        return (semi > 0 ? type.substring(0, semi) : type).toLowerCase(Locale.ROOT);
    }

    public boolean isZip(String mime) {
        return ZIP.equals(mime);
    }

    public boolean isSupported(String mime) {
        return SUPPORTED.contains(mime);
    }

    /** Untrusted files are parsed one or two at a time, each with a time limit (a hostile file cannot pin threads). */
    static final Duration PARSE_TIMEOUT = Duration.ofSeconds(60);
    private final Semaphore permits = new Semaphore(2);
    private final ExecutorService parsers = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("tika-", 0).daemon().factory());

    @PreDestroy
    void shutdown() {
        parsers.shutdownNow();
    }

    public Extraction extract(byte[] content, String mime) {
        if (!isSupported(mime)) {
            return new Extraction("UNSUPPORTED", null, null, "Unsupported file type: " + mime);
        }
        try {
            if (!permits.tryAcquire(PARSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                return new Extraction("FAILED", null, null, "Server busy – try again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Extraction("FAILED", null, null, "Interrupted");
        }
        Future<Extraction> job = parsers.submit(() -> parse(content, mime));
        try {
            return job.get(PARSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            job.cancel(true);
            return new Extraction("FAILED", null, null, "Reading the file took too long");
        } catch (ExecutionException e) {
            return new Extraction("FAILED", null, null, "Could not read the file");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.cancel(true);
            return new Extraction("FAILED", null, null, "Interrupted");
        } finally {
            permits.release();
        }
    }

    private Extraction parse(byte[] content, String mime) {
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARS);
        Metadata metadata = new Metadata();
        try (InputStream in = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                return new Extraction("FAILED", null, null, truncate("Could not read the file: " + e.getMessage()));
            }
        }
        String text = normalize(handler.toString());
        Integer pages = intOrNull(metadata.get("xmpTPg:NPages"));
        if (PDF.equals(mime)) {
            int p = pages == null ? 1 : Math.max(1, pages);
            // Scans without OCR text cannot be cited by the model; report them instead of producing uncited output.
            if (text.length() < 40 * p) {
                return new Extraction("NO_TEXT_LAYER", pages, null,
                        "The PDF has no text layer (scanned pages); OCR is not available yet");
            }
            return new Extraction("DONE", pages, null, null);
        }
        if (text.isBlank()) {
            return new Extraction("FAILED", pages, null, "No text found in the file");
        }
        return new Extraction("DONE", pages, text, null);
    }

    /** Supported files inside a ZIP archive. Nested archives, directories and hidden files are skipped. */
    public List<Entry> unzip(byte[] zip, long maxEntryBytes) throws IOException {
        // Decompressed archives live in memory: at most two at a time across the server.
        try {
            if (!permits.tryAcquire(PARSE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("Server busy");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        try {
            return unzip(zip, maxEntryBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedName) {
            return unzip(zip, maxEntryBytes, Charset.forName("IBM437"));
        } finally {
            permits.release();
        }
    }

    private List<Entry> unzip(byte[] zip, long maxEntryBytes, Charset charset) throws IOException {
        List<Entry> out = new ArrayList<>();
        long total = 0;
        int seen = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), charset)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++seen > MAX_ZIP_ENTRIES * 4) {
                    break;
                }
                String name = baseName(e.getName());
                if (e.isDirectory() || name.isEmpty() || name.startsWith(".") || e.getName().contains("__MACOSX")) {
                    continue;
                }
                byte[] data = readLimited(in, maxEntryBytes);
                if (data == null) {
                    continue; // entry larger than the per-file limit
                }
                total += data.length;
                if (total > MAX_ZIP_TOTAL_BYTES) {
                    throw new IOException("Archive too large after decompression");
                }
                if (isSupported(detect(data, name))) {
                    out.add(new Entry(name, data));
                    if (out.size() >= MAX_ZIP_ENTRIES) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static byte[] readLimited(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    static String baseName(String path) {
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1).strip();
        name = name.replaceAll("[\\p{Cntrl}]", "");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    static String normalize(String text) {
        return text.replace(' ', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .strip();
    }

    private static Integer intOrNull(String v) {
        try {
            return v == null ? null : Integer.valueOf(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}

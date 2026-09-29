package com.deepresearch.evidence;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every hop uses validated DNS addresses and a transport that pins the connection. */
public final class SafeWebReader implements SourceReader {
    public static final int MAX_BYTES = 262144, MAX_CODEPOINTS = 10000, MAX_REDIRECTS = 3;
    public static final Duration DEADLINE = Duration.ofSeconds(12);
    public interface Resolver { List<InetAddress> resolve(String host) throws Exception; }
    public interface Transport { Response fetch(URI uri, List<InetAddress> addresses, Duration remaining, int maxBytes) throws Exception; }
    public record Response(int status, Map<String, String> headers, byte[] body, InetAddress peer) { }
    private final Resolver resolver;
    private final Transport transport;
    private static final java.util.concurrent.ThreadPoolExecutor DNS = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8),
            task -> { Thread thread = new Thread(task, "evidence-bounded-dns"); thread.setDaemon(true); return thread; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    public SafeWebReader() { this(host -> List.of(InetAddress.getAllByName(host)), new PinnedHttpTransport()); }
    public SafeWebReader(Resolver resolver, Transport transport) { this.resolver = resolver; this.transport = transport; }

    @Override public Document read(EvidenceAuthority.Grant grant, EvidenceAuthority.Candidate candidate) {
        try {
            URI current = URI.create(candidate.url());
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                validateUri(current);
                String hostname = host(current);
                var resolution = DNS.submit(() -> resolver.resolve(hostname));
                List<InetAddress> addresses;
                try { addresses = resolution.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); }
                finally { resolution.cancel(true); }
                if (addresses.isEmpty() || addresses.stream().anyMatch(a -> !publicAddress(a)))
                    throw new EvidenceException("SOURCE_ADDRESS_DENIED");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new EvidenceException("SOURCE_TIMEOUT");
                Response response = transport.fetch(current, addresses, Duration.ofNanos(remaining), MAX_BYTES);
                if (System.nanoTime() > deadline) throw new EvidenceException("SOURCE_TIMEOUT");
                if (!addresses.contains(response.peer()) || !publicAddress(response.peer()))
                    throw new EvidenceException("SOURCE_DNS_BINDING_CHANGED");
                if (response.body() == null || response.body().length > MAX_BYTES)
                    throw new EvidenceException("SOURCE_TOO_LARGE");
                if (List.of(301, 302, 303, 307, 308).contains(response.status())) {
                    String location = response.headers().get("location");
                    if (location == null || hop == MAX_REDIRECTS) throw new EvidenceException("SOURCE_REDIRECT_DENIED");
                    URI next = current.resolve(location);
                    if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme()))
                        throw new EvidenceException("SOURCE_REDIRECT_DOWNGRADE");
                    current = next; continue;
                }
                if (response.status() != 200) throw new EvidenceException("SOURCE_HTTP_FAILURE");
                String encoding = response.headers().getOrDefault("content-encoding", "identity");
                if (!encoding.equalsIgnoreCase("identity")) throw new EvidenceException("SOURCE_ENCODING_UNSUPPORTED");
                String type = response.headers().getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
                if (!(type.startsWith("text/html") || type.startsWith("text/plain")))
                    throw new EvidenceException("SOURCE_TYPE_UNSUPPORTED");
                if (type.contains("charset=") && !type.matches(".*charset=\"?utf-8\"?(?:\\s*;.*|\\s*)"))
                    throw new EvidenceException("SOURCE_ENCODING_UNSUPPORTED");
                String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
                String extracted = type.startsWith("text/html") ? htmlText(raw) : raw;
                extracted = extracted.replace("\r\n", "\n").replace('\r', '\n').trim();
                if (extracted.isBlank()) throw new EvidenceException("SOURCE_TEXT_MISSING");
                boolean truncated = extracted.codePointCount(0, extracted.length()) > MAX_CODEPOINTS;
                String text = truncated ? extracted.substring(0, extracted.offsetByCodePoints(0, MAX_CODEPOINTS)) : extracted;
                return new Document(text, candidate.title(), EvidenceJson.object("kind", "web_uri", "uri", current.toASCIIString()),
                        truncated ? "document_chunk" : "full_text", Instant.now(), EvidenceJson.sha(response.body()), truncated, false);
            }
            throw new EvidenceException("SOURCE_REDIRECT_DENIED");
        } catch (EvidenceException failure) { throw failure; }
        catch (Exception failure) { throw new EvidenceException("SOURCE_READ_FAILED"); }
    }

    static String host(URI uri) { return uri.getHost().replace("[", "").replace("]", ""); }
    public static void validateUri(URI uri) {
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.toString().length() > 2048
                || !uri.toString().equals(uri.toASCIIString()) || uri.toString().matches(".*[\\s\\p{Cntrl}\\\\].*"))
            throw new EvidenceException("SOURCE_URI_DENIED");
        int expectedPort = "https".equals(uri.getScheme()) ? 443 : 80;
        if (uri.getPort() != -1 && uri.getPort() != expectedPort || host(uri).contains("%"))
            throw new EvidenceException("SOURCE_URI_DENIED");
    }

    /** Conservative public unicast policy; rejects transition/documentation/reserved ranges. */
    public static boolean publicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192 && (c == 168 || c == 0 && (d == 0 || d == 2) || c == 88 && d == 99))
                    && !(a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)) && !(a == 203 && c == 0 && d == 113);
        }
        if (b.length != 16 || (b[0] & 0xe0) != 0x20) return false; // 2000::/3 only; no mapped/NAT64/ULA
        if ((b[0] & 255) == 0x20 && (b[1] & 255) == 1) {
            if ((b[2] & 0xfe) == 0 || (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8) return false;
        }
        return !((b[0] & 255) == 0x20 && (b[1] & 255) == 2)
                && !((b[0] & 255) == 0x3f && (b[1] & 255) == 0xff && (b[2] & 0xf0) == 0);
    }

    static String htmlText(String html) throws Exception {
        StringBuilder out = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            int ignored;
            public void handleStartTag(HTML.Tag tag, MutableAttributeSet a, int p) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) ignored++;
                else if (ignored == 0 && tag.isBlock()) out.append('\n');
            }
            public void handleEndTag(HTML.Tag tag, int p) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE || tag == HTML.Tag.HEAD) ignored = Math.max(0, ignored - 1);
                else if (ignored == 0 && tag.isBlock()) out.append('\n');
            }
            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet a, int p) { if (ignored == 0 && tag == HTML.Tag.BR) out.append('\n'); }
            public void handleText(char[] text, int p) { if (ignored == 0) out.append(text).append(' '); }
        }, true);
        return out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
    }
}

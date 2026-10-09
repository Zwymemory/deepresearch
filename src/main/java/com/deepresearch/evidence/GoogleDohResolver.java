package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Opt-in public DNS over HTTPS. Bootstrap IP is pinned; TLS verifies dns.google.
 * No OS DNS, redirects, proxy hostname resolution, or fallback to synthetic addresses.
 * JSON contract: https://developers.google.com/speed/public-dns/docs/doh/json
 */
public final class GoogleDohResolver implements SafeWebReader.Resolver {
    private static final Duration LIMIT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 16384;
    private final SafeWebReader.Transport transport;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDohResolver() { this(new PinnedHttpTransport()); }
    GoogleDohResolver(SafeWebReader.Transport transport) { this.transport = transport; }

    @Override public List<InetAddress> resolve(String host) throws Exception {
        // Numeric targets must retain their identity, including private/reserved addresses.
        // Never resolve an IP literal as a domain through a public resolver.
        if (host.contains(":") || host.matches("[0-9.]+")) return List.of(literal(host));
        String name = canonical(host);
        if (!name.contains(".") || name.endsWith(".localhost") || name.endsWith(".local")
                || name.endsWith(".internal") || name.endsWith(".home.arpa"))
            throw new EvidenceException("SOURCE_ADDRESS_DENIED");
        long deadline = System.nanoTime() + LIMIT.toNanos();
        var addresses = new LinkedHashSet<InetAddress>();
        for (int type : List.of(1, 28)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new EvidenceException("SOURCE_TIMEOUT");
            URI endpoint = URI.create("https://dns.google/resolve?name="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&type=" + type + "&edns_client_subnet=0.0.0.0/0");
            InetAddress bootstrap = InetAddress.getByAddress(new byte[]{8,8,8,8});
            var response = transport.fetch(endpoint, List.of(bootstrap), Duration.ofNanos(remaining), MAX_BYTES);
            if (System.nanoTime() > deadline) throw new EvidenceException("SOURCE_TIMEOUT");
            if (!bootstrap.equals(response.peer())) throw new EvidenceException("SOURCE_DNS_BINDING_CHANGED");
            if (response.status() != 200 || response.body() == null || response.body().length > MAX_BYTES
                    || !response.headers().getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("application/json")
                    || !response.headers().getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity"))
                throw new EvidenceException("SOURCE_DNS_FAILED");
            addresses.addAll(parse(json.readTree(response.body()), name, type));
        }
        return List.copyOf(addresses);
    }

    static List<InetAddress> parse(JsonNode root, String name, int type) throws Exception {
        JsonNode questions = root.path("Question");
        if (!root.path("Status").isIntegralNumber() || root.path("Status").intValue() != 0
                || !root.path("TC").isBoolean() || root.path("TC").booleanValue()
                || !questions.isArray() || questions.size() != 1
                || !canonical(questions.get(0).path("name").asText()).equals(name)
                || questions.get(0).path("type").asInt(-1) != type)
            throw new EvidenceException("SOURCE_DNS_FAILED");
        JsonNode answer = root.get("Answer");
        if (answer == null) return List.of(); // NOERROR/NODATA for this family
        if (!answer.isArray() || answer.size() > 64) throw new EvidenceException("SOURCE_DNS_FAILED");
        var names = new LinkedHashSet<String>(); names.add(name);
        // Bind address records to the queried name or its authenticated CNAME chain.
        for (int hop = 0; hop < 16; hop++) {
            boolean changed = false;
            for (JsonNode rr : answer) {
                if (rr.path("type").asInt() == 5 && names.contains(canonical(rr.path("name").asText())))
                    changed |= names.add(canonical(rr.path("data").asText()));
            }
            if (!changed) break;
            if (hop == 15) throw new EvidenceException("SOURCE_DNS_FAILED");
        }
        var addresses = new ArrayList<InetAddress>();
        for (JsonNode rr : answer) {
            if (rr.path("type").asInt() != type) continue;
            if (!names.contains(canonical(rr.path("name").asText()))) throw new EvidenceException("SOURCE_DNS_FAILED");
            InetAddress ip = literal(rr.path("data").asText());
            if (type == 1 && ip.getAddress().length != 4 || type == 28 && ip.getAddress().length != 16)
                throw new EvidenceException("SOURCE_DNS_FAILED");
            // Mixed public/private answers are denied, never filtered into apparent success.
            if (!SafeWebReader.publicAddress(ip)) throw new EvidenceException("SOURCE_ADDRESS_DENIED");
            addresses.add(ip);
        }
        return addresses;
    }

    static String canonical(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() > 253 || !value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*"))
            throw new EvidenceException("SOURCE_DNS_FAILED");
        return value;
    }

    static InetAddress literal(String text) throws Exception {
        if (text.contains(":") && text.matches("[a-fA-F0-9:.]+")) return InetAddress.getByName(text);
        if (!text.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) throw new EvidenceException("SOURCE_DNS_FAILED");
        byte[] bytes = new byte[4]; String[] parts = text.split("\\.");
        for (int i = 0; i < 4; i++) {
            int n = Integer.parseInt(parts[i]);
            if (n > 255 || parts[i].length() > 1 && parts[i].startsWith("0")) throw new EvidenceException("SOURCE_DNS_FAILED");
            bytes[i] = (byte) n;
        }
        return InetAddress.getByAddress(bytes);
    }
}

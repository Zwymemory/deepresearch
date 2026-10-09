package com.deepresearch.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GoogleDohResolverTest {
    static String answer(String records, int type) {
        return "{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\"example.org.\",\"type\":" + type
                + "}],\"Answer\":[" + records + "]}";
    }
    static String rr(String name, int type, String data) {
        return "{\"name\":\"" + name + "\",\"type\":" + type + ",\"data\":\"" + data + "\"}";
    }
    static List<InetAddress> parse(String body, int type) throws Exception {
        return GoogleDohResolver.parse(new ObjectMapper().readTree(body), "example.org", type);
    }
    static SafeWebReader.Response response(String body, String peer) {
        return SafeWebReaderTest.response(200, Map.of("content-type", "application/json; charset=UTF-8"), body, peer);
    }
    @Test void followsAuthenticatedAliasChainAndChecksBothFamiliesWithPinnedBootstrap() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new GoogleDohResolver((uri, addresses, remaining, max) -> {
            assertThat(uri.getHost()).isEqualTo("dns.google");
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getRawQuery()).contains("name=example.org", "edns_client_subnet=0.0.0.0/0");
            assertThat(addresses).containsExactly(SafeWebReaderTest.ip("8.8.8.8"));
            assertThat(max).isEqualTo(16384);
            assertThat(remaining.toMillis()).isPositive().isLessThanOrEqualTo(5000);
            int type = calls.getAndIncrement() == 0 ? 1 : 28;
            return response(answer(rr("example.org.",5,"cdn.example.net.") + ","
                    + rr("cdn.example.net.", type, type == 1 ? "151.101.0.223" : "2606:4700::1111"), type), "8.8.8.8");
        });
        assertThat(resolver.resolve("Example.org.")).containsExactly(
                SafeWebReaderTest.ip("151.101.0.223"), SafeWebReaderTest.ip("2606:4700::1111"));
        assertThat(calls).hasValue(2);
    }
    @Test void invalidDnsResponsesAreNotUsedAsTargets() {
        for (String body : List.of(answer(rr("example.org.",1,"8.8.8.8"),1).replace("\"Status\":0", "\"Status\":2"),
                answer(rr("example.org.",1,"8.8.8.8"),1).replace("\"TC\":false", "\"TC\":true"),
                answer(rr("example.org.",1,"8.8.8.8"),1).replace("\"Question\":[", "\"Other\":["),
                answer(rr("unrelated.org.",1,"8.8.8.8"),1),
                answer(rr("example.org.",1,"host.example.net"),1),
                answer(rr("example.org.",1,"999.1.1.1"),1),
                answer(rr("example.org.",1,"010.0.0.1"),1),
                answer(rr("example.org.",1,"2606:4700::1111"),1),
                answer(rr("example.org.",28,"8.8.8.8"),28)))
            assertThatThrownBy(() -> parse(body, body.contains("\"type\":28") ? 28 : 1)).hasMessageContaining("SOURCE_DNS_FAILED");
    }
    @Test void mixedAnswersAndReservedIpv6RemainDenied() {
        for (String ip : List.of("127.0.0.1", "169.254.169.254", "198.18.0.159", "10.0.0.1"))
            assertThatThrownBy(() -> parse(answer(rr("example.org.",1,"8.8.8.8") + "," + rr("example.org.",1,ip),1),1))
                    .hasMessageContaining("SOURCE_ADDRESS_DENIED");
        assertThatThrownBy(() -> parse(answer(rr("example.org.",28,"2001:2::9e"),28),28))
                .hasMessageContaining("SOURCE_ADDRESS_DENIED");
    }
    @Test void aPrivateAaaaCannotBeHiddenByAPublicA() {
        var resolver = new GoogleDohResolver((u,a,t,m) -> response(u.getQuery().contains("type=1&")
                ? answer(rr("example.org.",1,"8.8.8.8"),1) : answer(rr("example.org.",28,"fd00::1"),28), "8.8.8.8"));
        assertThatThrownBy(() -> resolver.resolve("example.org")).hasMessageContaining("SOURCE_ADDRESS_DENIED");
    }
    @Test void literalAndLocalTargetsNeverReachExternalDnsOrWebTransport() throws Exception {
        var calls = new AtomicInteger();
        var resolver = new GoogleDohResolver((u,a,t,m) -> { calls.incrementAndGet(); throw new AssertionError("network called"); });
        for (String ip : List.of("127.0.0.1", "169.254.169.254", "198.18.0.159", "::1", "2001:2::9e")) {
            assertThat(resolver.resolve(ip)).containsExactly(SafeWebReaderTest.ip(ip));
            var reader = new SafeWebReader(resolver, (u,a,t,m) -> { calls.incrementAndGet(); return null; });
            assertThatThrownBy(() -> reader.read(null, SafeWebReaderTest.candidate("https://" + (ip.contains(":") ? "["+ip+"]" : ip) + "/")))
                    .hasMessageContaining("SOURCE_ADDRESS_DENIED");
        }
        for (String host : List.of("localhost", "foo.local", "foo.localhost", "foo.internal", "foo.home.arpa"))
            assertThatThrownBy(() -> resolver.resolve(host)).hasMessageContaining("SOURCE_ADDRESS_DENIED");
        assertThat(calls).hasValue(0);
    }
    @Test void redirectsEncodingOversizedBodiesAndChangedBootstrapAreDenied() {
        for (var response : List.of(new SafeWebReader.Response(302,Map.of("location","https://evil.org"),new byte[0],SafeWebReaderTest.ip("8.8.8.8")),
                new SafeWebReader.Response(200, Map.of("content-type","application/json"),new byte[16385],SafeWebReaderTest.ip("8.8.8.8")),
                new SafeWebReader.Response(200,Map.of("content-type","application/json","content-encoding","gzip"),new byte[0],SafeWebReaderTest.ip("8.8.8.8")),
                response("{}", "127.0.0.1"))) {
            var resolver = new GoogleDohResolver((u,a,t,m) -> response);
            assertThatThrownBy(() -> resolver.resolve("example.org")).isInstanceOf(EvidenceException.class);
        }
    }
    @Test void missingFamilyIsAllowedButEmptyBothFamiliesCannotReadAnySource() throws Exception {
        assertThat(parse(answer("",28),28)).isEmpty();
        var resolver = new GoogleDohResolver((u,a,t,m) -> response(answer("", u.getQuery().contains("type=1&") ? 1 : 28), "8.8.8.8"));
        var reader = new SafeWebReader(resolver, (u,a,t,m) -> { throw new AssertionError("no target"); });
        assertThatThrownBy(() -> reader.read(null,SafeWebReaderTest.candidate("https://example.org/"))).hasMessageContaining("SOURCE_ADDRESS_DENIED");
    }
    @Test void aRedirectToPrivateDnsIsDeniedAfterPublicDohRead() {
        var resolver = new GoogleDohResolver((u,a,t,m) -> response(answer(u.getQuery().contains("type=1&")
                ? rr("example.org.",1,"8.8.8.8") : "", u.getQuery().contains("type=1&") ? 1 : 28), "8.8.8.8"));
        var calls = new AtomicInteger();
        var reader = new SafeWebReader(resolver,(u,a,t,m) -> {
            calls.incrementAndGet(); return SafeWebReaderTest.response(302,Map.of("location","https://169.254.169.254/latest"),"","8.8.8.8");
        });
        assertThatThrownBy(() -> reader.read(null,SafeWebReaderTest.candidate("https://example.org/"))).hasMessageContaining("SOURCE_ADDRESS_DENIED");
        assertThat(calls).hasValue(1);
    }
}

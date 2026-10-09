package com.deepresearch.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TavilySearchClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tavily.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TavilySearchClient client = new TavilySearchClient("test-not-a-real-secret", builder.build());

    @Test
    void missingConfigurationMakesNoHttpRequest() {
        var missing = new TavilySearchClient("", builder.build());
        assertThat(missing.configured()).isFalse();
        assertThat(missing.searchChecked("query", 5).code()).isEqualTo("WEB_SEARCH_NOT_CONFIGURED");
        server.verify();
    }

    @Test
    void emptyResultsAreSuccessfulAndTypedHitsKeepTheirActualUrlAndSummary() {
        server.expect(requestTo("https://api.tavily.com/search"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        var empty = client.searchChecked("query", 5);
        assertThat(empty.code()).isEqualTo("OK");
        assertThat(empty.hits()).isEmpty();
        server.verify();
        server.reset();
        server.expect(requestTo("https://api.tavily.com/search")).andRespond(withSuccess(
                "{\"results\":[{\"title\":\"Page\",\"url\":\"https://example.com/page\",\"content\":\"actual search summary\",\"score\":0.8}]}",
                MediaType.APPLICATION_JSON));
        var found = client.searchChecked("query", 5);
        assertThat(found.hits().get(0).url()).isEqualTo("https://example.com/page");
        assertThat(found.hits().get(0).content()).isEqualTo("actual search summary");
        server.verify();
    }

    @Test
    void timeoutAndProviderFailureHaveSeparateSafeCodesWithoutProviderBody() {
        server.expect(requestTo("https://api.tavily.com/search"))
                .andRespond(withException(new SocketTimeoutException("secret provider text")));
        assertThat(client.searchChecked("query", 5).code()).isEqualTo("WEB_SEARCH_TIMEOUT");
        server.verify();
        server.reset();
        server.expect(requestTo("https://api.tavily.com/search"))
                .andRespond(withServerError().body("secret provider text"));
        var failure = client.searchChecked("query", 5);
        assertThat(failure.code()).isEqualTo("WEB_SEARCH_PROVIDER_UNAVAILABLE");
        assertThat(failure.hits()).isEmpty();
        server.verify();
    }

    @Test
    void siteConstraintIsSentAndEnforcedAgainstEachActualResultHostname() {
        server.expect(requestTo("https://api.tavily.com/search"))
                .andExpect(content().json("{\"include_domains\":[\"example.org\"],\"include_domains_mode\":\"restrict\"}"))
                .andRespond(withSuccess("""
                    {"results":[
                      {"url":"https://example.org/a","content":"root"},
                      {"url":"https://docs.example.org/b","content":"subdomain"},
                      {"url":"https://notexample.org/c","content":"wrong suffix"},
                      {"url":"https://example.org.evil.com/d","content":"wrong prefix"},
                      {"url":"https://example.org\u0040evil.com/e","content":"user info"},
                      {"url":"https://user\u0040example.org/f","content":"credentials"},
                      {"url":"https://evil.com/?host=example.org","content":"query"},
                      {"url":"file://example.org/a","content":"wrong scheme"}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        var found = client.searchChecked("topic SITE:example.org", 5);
        assertThat(found.code()).isEqualTo("OK");
        assertThat(found.hits()).extracting(hit -> hit.url()).containsExactly(
                "https://example.org/a", "https://docs.example.org/b");
        server.verify();
    }

    @Test
    void malformedAndUnboundedSiteConstraintsNeverCallProvider() {
        for (String scope : new String[]{"site:", "site:https://example.org", "site:example.org/a/../b",
                "site:example.org/a?query=1", "site:example.org/a%2fb",
                "site:*.example.org", "site:example.org:443", "site:127.0.0.1",
                "site:a.org site:b.org site:c.org site:d.org"}) {
            assertThat(client.searchChecked("topic " + scope, 5).code()).isEqualTo("INVALID_ARGUMENT");
        }
        server.verify();
    }

    @Test void sitePathBecomesProviderDomainFilterAndRemainsAPostFilter() {
        server.expect(requestTo("https://api.tavily.com/search"))
                .andExpect(content().json("{\"query\":\"constructor injection\",\"include_domains\":[\"docs.spring.io\"]}"))
                .andRespond(withSuccess("""
                    {"results":[
                      {"url":"https://docs.spring.io/spring-boot/reference/using/beans.html","content":"match"},
                      {"url":"https://docs.spring.io/spring-framework/beans.html","content":"other product"},
                      {"url":"https://docs.spring.io/spring-boot-old/beans.html","content":"wrong prefix"},
                      {"url":"https://docs.spring.io.evil.org/spring-boot/a","content":"wrong host"},
                      {"url":"https://docs.spring.io/spring-boot/../other","content":"traversal"}
                    ]}
                    """, MediaType.APPLICATION_JSON));
        var result = client.searchChecked("site:docs.spring.io/spring-boot constructor injection",5);
        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.hits()).extracting(hit -> hit.url()).containsExactly(
                "https://docs.spring.io/spring-boot/reference/using/beans.html");
        server.verify();
    }

    @Test
    void allOutOfScopeResultsStayEmptyWithoutRetryOrDomainFallback() {
        server.expect(requestTo("https://api.tavily.com/search"))
                .andRespond(withSuccess("{\"results\":[{\"url\":\"https://other.org/a\",\"content\":\"wrong domain\"}]}",
                        MediaType.APPLICATION_JSON));
        var found = client.searchChecked("topic site:example.org", 5);
        assertThat(found.code()).isEqualTo("OK");
        assertThat(found.hits()).isEmpty();
        server.verify();
    }
}

package com.deepresearch.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
}

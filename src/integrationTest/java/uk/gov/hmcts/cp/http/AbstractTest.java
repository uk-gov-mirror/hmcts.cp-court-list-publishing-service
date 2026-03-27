package uk.gov.hmcts.cp.http;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Base class for integration test classes that use WireMock.
 * Resets WireMock stub mappings before each test class runs so tests start from a clean state
 * (reloads mappings from the static files under wiremock/mappings).
 * <p>
 * WireMock runs in Docker; from the host the admin API is on port 8089 (see docker-compose).
 */
public abstract class AbstractTest {

    public static final String WIREMOCK_BASE_URL =
            System.getProperty("wiremock.baseUrl", "http://localhost:8089");

    public static final String WIREMOCK_ADMIN_MAPPINGS_RESET = WIREMOCK_BASE_URL + "/__admin/mappings/reset";
    public static final String WIREMOCK_ADMIN_MAPPINGS = WIREMOCK_BASE_URL + "/__admin/mappings";
    public static final String WIREMOCK_ADMIN_REQUESTS = WIREMOCK_BASE_URL + "/__admin/requests";

    /** CaTH publish path (leading slash); matches default {@code cath.endpoint}. */
    public static final String CATH_PUBLICATION_URL_PATH = "/courtlistpublisher/publication";

    @BeforeAll
    static void initTest() {
        resetWireMock();
    }

    static void resetWireMock() {
        RestTemplate rest = new RestTemplate();
        ResponseEntity<String> response = rest.exchange(
                WIREMOCK_ADMIN_MAPPINGS_RESET,
                HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "WireMock reset failed: " + response.getStatusCode() + " from " + WIREMOCK_ADMIN_MAPPINGS_RESET);
        }
    }
}

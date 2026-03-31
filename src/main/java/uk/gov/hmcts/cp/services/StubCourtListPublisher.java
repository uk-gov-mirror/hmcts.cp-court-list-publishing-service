package uk.gov.hmcts.cp.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.domain.DtsMeta;

/**
 * Court list publisher used when profile {@code integration} is active.
 * When {@code cath.base-url} is set (e.g. in Docker to WireMock), performs HTTP POST so tests can stub success/failure.
 * When not set, no-ops and returns 200 so startup still works.
 */
@Component
@Primary
@Profile("integration")
@Slf4j
public class StubCourtListPublisher implements CourtListPublisher {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String cathBaseUrl;
    private final String cathEndpoint;

    public StubCourtListPublisher(
            @Value("${cath.base-url:}") String cathBaseUrl,
            @Value("${cath.endpoint:/courtlistpublisher/publication}") String cathEndpoint) {
        this.cathBaseUrl = cathBaseUrl != null ? cathBaseUrl.stripTrailing() : "";
        this.cathEndpoint = cathEndpoint != null && cathEndpoint.startsWith("/") ? cathEndpoint : "/" + cathEndpoint;
    }

    @Override
    public int publish(String payload, DtsMeta metadata) {
        if (cathBaseUrl.isBlank()) {
            log.debug("StubCourtListPublisher: cath.base-url not set, no-op publish (integration profile)");
            return HttpStatus.OK.value();
        }
        String url = cathBaseUrl + cathEndpoint;
        log.info("StubCourtListPublisher: POST to {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Mirror PublishingService metadata headers so integration tests can validate DtsMeta-to-header mapping.
        if (metadata != null) {
            headers.set("x-provenance", metadata.getProvenance());
            headers.set("x-type", metadata.getType());
            headers.set("x-list-type", metadata.getListType());
            headers.set("x-court-id", metadata.getCourtId());
            headers.set("x-content-date", metadata.getContentDate());
            headers.set("x-language", metadata.getLanguage());
            headers.set("x-sensitivity", metadata.getSensitivity());
            headers.set("x-display-from", metadata.getDisplayFrom());
            headers.set("x-display-to", metadata.getDisplayTo());
        }
        var response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                String.class);
        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("CaTH returned non-2xx: {}", status);
            throw new RuntimeException("CaTH returned " + status + ": " + response.getBody());
        }
        return status;
    }
}

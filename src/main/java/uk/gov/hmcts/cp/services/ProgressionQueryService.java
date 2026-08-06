package uk.gov.hmcts.cp.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import uk.gov.hmcts.cp.config.AppConstant;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.net.URI;

/**
 * Calls progression service GET /courtlistdata (application/vnd.progression.search.court.list.data+json).
 * Returns the same court list data shape as progression's court list document payload, without PDF/Word generation.
 * CJSCPPUID (user ID) must always be provided; it is sent on the User-Id header to progression.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgressionQueryService {

    private static final String PROGRESSION_COURTLISTDATA_PATH = "/progression-service/query/api/rest/progression/courtlistdata";
    private static final String ACCEPT_COURT_LIST_DATA = "application/vnd.progression.search.court.list.data+json";
    private static final String ACCEPT_PRISON_COURT_LIST_DATA = "application/vnd.progression.search.prison.court.list.data+json";
    private static final String PRISON = "PRISON";


    private final RestTemplate restTemplate;

    @Value("${common-platform-query-api.base-url:}")
    private String baseUrl;

    /**
     * Fetches court list data from progression /courtlistdata.
     * Returns raw JSON string (same shape as progression court list payload).
     *
     * @param cjscppuid user ID for the request (required); sent on User-Id header to progression.
     */
    public String getCourtListPayload(
            CourtListType listId,
            String courtCentreId,
            String courtRoomId,
            String startDate,
            String endDate,
            boolean restricted,
            String cjscppuid,
            boolean includeApplications) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("common-platform-query-api.base-url is not configured");
        }
        if (cjscppuid == null || cjscppuid.isBlank()) {
            throw new IllegalArgumentException("CJSCPPUID (user ID) is required when calling progression courtlistdata");
        }
        log.info("Fetching court list data from progression for listId: {}, courtCentreId: {}, startDate: {}, endDate: {}",
                listId, courtCentreId, startDate, endDate);

        var builder = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path(PROGRESSION_COURTLISTDATA_PATH)
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .queryParam("restricted", restricted)
                .queryParam("includeApplications", includeApplications)
                // listId is required by progression /courtlistdata for every list type, including PRISON;
                // the prison variant is selected by the Accept media type, not by omitting listId.
                .queryParam("listId", listId.name());
        if (courtRoomId != null && !courtRoomId.isBlank()) {
            builder.queryParam("courtRoomId", courtRoomId);
        }
        URI uri = builder.build().toUri();

        String accept = PRISON.equals(listId.name()) ? ACCEPT_PRISON_COURT_LIST_DATA : ACCEPT_COURT_LIST_DATA;
        log.debug("Calling progression courtlistdata with URI: {}, Accept: {}", uri, accept);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", accept);
        headers.set(AppConstant.CJSCPPUID, cjscppuid);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                requestEntity,
                String.class
        );
        log.info("Successfully retrieved court list data from progression");
        return response.getBody() != null ? response.getBody() : "{}";
    }
}

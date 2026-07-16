package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.config.AppConstant;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Service
@Slf4j
public class CourtListDataService {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String COURT_LIST_PAYLOAD_PATH = "/listing-service/query/api/rest/listing/courtlistpayload";
    private static final String DAILY_LIST_PAYLOAD_PATH = "/listing-service/query/api/rest/listing/dailylistpayload";
    private static final String COURT_LIST_PATH = "/listing-service/query/api/rest/listing/courtlist";
    private static final String ACCEPT_COURTLIST_PAYLOAD = "application/vnd.listing.search.court.list.payload+json";
    private static final String ACCEPT_CROWN_DAILY_LIST_PAYLOAD = "application/vnd.listing.search.daily.list.payload+json";
    private static final String PROGRESSION_COURTLIST_PATH = "/progression-service/query/api/rest/progression/courtlist";
    private static final String ACCEPT_PROGRESSION_COURTLIST = "application/vnd.progression.search.court.list+json";

    private static final Set<CourtListType> PROGRESSION_ENRICHED_TYPES = EnumSet.of(
            CourtListType.PUBLIC,
            CourtListType.STANDARD,
            CourtListType.BENCH,
            CourtListType.ONLINE_PUBLIC,
            CourtListType.USHERS_CROWN,
            CourtListType.USHERS_MAGISTRATE,
            CourtListType.PRISON);

    private final ProgressionQueryService progressionQueryService;
    private final RestTemplate publicCourtListRestTemplate;
    private final String courtListDataBaseUrl;

    public CourtListDataService(
            final ProgressionQueryService progressionQueryService,
            final RestTemplate publicCourtListRestTemplate,
            @Value("${common-platform-query-api.base-url:}") final String courtListDataBaseUrl) {
        this.progressionQueryService = progressionQueryService;
        this.publicCourtListRestTemplate = publicCourtListRestTemplate;
        this.courtListDataBaseUrl = courtListDataBaseUrl != null ? courtListDataBaseUrl : "";
    }

    public String getCourtListData(
            CourtListType listId,
            String courtCentreId,
            String courtRoomId,
            String startDate,
            String endDate,
            boolean restricted,
            String currentUserId,
            boolean includeApplications) {
        if (PROGRESSION_ENRICHED_TYPES.contains(listId)) {
            return progressionQueryService.getCourtListPayload(
                    listId, courtCentreId, courtRoomId, startDate, endDate, restricted, currentUserId, includeApplications);
        }
        return fetchCourtListPayloadFromListing(
                listId, courtCentreId, courtRoomId, startDate, endDate, restricted, includeApplications, currentUserId);
    }

    public CourtListPayload getCourtListPayload(
            CourtListType listId,
            String courtCentreId,
            String startDate,
            String endDate,
            String cjscppuid,
            boolean includeApplications) {
        boolean restricted = cjscppuid != null && !cjscppuid.isBlank();
        String json = getCourtListData(listId, courtCentreId, null, startDate, endDate, restricted, cjscppuid, includeApplications);

        try {
            return OBJECT_MAPPER.readValue(json, CourtListPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize court list data to CourtListPayload: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to parse court list payload", e);
        }
    }

    public String getCourtListPayloadForDownload(
            CourtListType courtListType, String courtCentreId, String courtRoomId,
            LocalDate startDate, LocalDate endDate, String cjscppuid, boolean restricted) {
        if (PROGRESSION_ENRICHED_TYPES.contains(courtListType)) {
            return progressionQueryService.getCourtListPayload(
                    courtListType, courtCentreId, courtRoomId,
                    startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT),
                    restricted, cjscppuid, false);
        }
        return fetchCourtListPayloadFromListing(
                courtListType, courtCentreId, courtRoomId,
                startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT),
                restricted, false, cjscppuid);
    }

    public byte[] fetchCourtListPdfFromProgression(
            CourtListType courtListType, String courtCentreId, String courtRoomId,
            LocalDate startDate, LocalDate endDate, String cjscppuid, boolean restricted) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }
        if (cjscppuid == null || cjscppuid.isBlank()) {
            throw new CourtListDownloadException("CJSCPPUID is required for progression /courtlist");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(courtListDataBaseUrl).path(PROGRESSION_COURTLIST_PATH)
                .queryParam("listId", courtListType.name())
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate.format(DATE_FORMAT))
                .queryParam("endDate", endDate.format(DATE_FORMAT))
                .queryParam("restricted", restricted);
        if (courtRoomId != null && !courtRoomId.isBlank()) {
            builder.queryParam("courtRoomId", courtRoomId);
        }
        String url = builder.build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.parseMediaType(ACCEPT_PROGRESSION_COURTLIST)));
        headers.set(AppConstant.CJSCPPUID, cjscppuid);

        try {
            ResponseEntity<byte[]> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new CourtListDownloadException("Progression /courtlist returned empty response");
            }
            return body;
        } catch (RestClientException e) {
            log.error("Progression /courtlist call failed for listId={}, courtCentreId={}", courtListType, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list document from progression: " + e.getMessage(), e);
        }
    }

    public byte[] fetchCourtListPdfFromListing(
            CourtListType courtListType, String courtCentreId, String courtRoomId,
            LocalDate startDate, LocalDate endDate, String cjscppuid, boolean restricted) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(courtListDataBaseUrl).path(COURT_LIST_PATH)
                .queryParam("listId", courtListType.name())
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate.format(DATE_FORMAT))
                .queryParam("endDate", endDate.format(DATE_FORMAT))
                .queryParam("restricted", restricted);
        if (courtRoomId != null && !courtRoomId.isBlank()) {
            builder.queryParam("courtRoomId", courtRoomId);
        }
        String url = builder.build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_PDF));
        if (cjscppuid != null && !cjscppuid.isBlank()) {
            headers.set(AppConstant.CJSCPPUID, cjscppuid);
        }

        try {
            ResponseEntity<byte[]> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new CourtListDownloadException("Court list PDF API returned empty response");
            }
            return body;
        } catch (RestClientException e) {
            log.error("Court list PDF API call failed for listId={}, courtCentreId={}", courtListType, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list PDF: " + e.getMessage(), e);
        }
    }

    public String getCrownCourtDailyListPayload(
            CourtListType courtListType, String courtCentreId, String courtRoomId,
            LocalDate startDate, LocalDate endDate, String cjscppuid, boolean restricted) {
        if (CourtListType.ALPHABETICAL.equals(courtListType)) {
            log.info("Fetching crown court alphabetical payload for courtCentreId={}, startDate={}, endDate={}",
                    courtCentreId, startDate, endDate);
            return fetchCourtListPayloadFromListing(
                    courtListType, courtCentreId, courtRoomId,
                    startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT),
                    restricted, false, cjscppuid,
                    ACCEPT_COURTLIST_PAYLOAD, COURT_LIST_PAYLOAD_PATH);
        }
        log.info("Fetching crown court daily list payload for publishCourtListType={}, courtCentreId={}, startDate={}, endDate={}",
                courtListType, courtCentreId, startDate, endDate);
        String result = fetchDailyListPayload(
                courtListType, courtCentreId,
                startDate.format(DATE_FORMAT), endDate.format(DATE_FORMAT), cjscppuid);
        log.info("Successfully fetched crown court daily list payload for publishCourtListType={}, courtCentreId={}", courtListType, courtCentreId);
        return result;
    }

    private String fetchDailyListPayload(
            CourtListType publishCourtListType, String courtCentreId,
            String startDate, String endDate, String cjscppuid) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(courtListDataBaseUrl).path(DAILY_LIST_PAYLOAD_PATH)
                .queryParam("publishCourtListType", publishCourtListType.name())
                .queryParam("courtCentreId", courtCentreId);
        if (CourtListType.FIRM.equals(publishCourtListType)) {
            builder.queryParam("weekCommencingStartDate", startDate)
                    .queryParam("weekCommencingEndDate", endDate);
        } else {
            builder.queryParam("startDate", startDate)
                    .queryParam("endDate", endDate);
        }
        String url = builder.build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.parseMediaType(ACCEPT_CROWN_DAILY_LIST_PAYLOAD)));
        if (cjscppuid != null && !cjscppuid.isBlank()) {
            headers.set(AppConstant.CJSCPPUID, cjscppuid);
        }

        try {
            ResponseEntity<String> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new CourtListDownloadException("Court list payload API returned empty response");
            }
            return body;
        } catch (RestClientException e) {
            log.error("Daily list payload API call failed for publishCourtListType={}, courtCentreId={}", publishCourtListType, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list payload: " + e.getMessage(), e);
        }
    }

    private String fetchCourtListPayloadFromListing(
            CourtListType listId, String courtCentreId, String courtRoomId,
            String startDate, String endDate, boolean restricted, boolean includeApplications, String cjscppuid) {
        return fetchCourtListPayloadFromListing(listId, courtCentreId, courtRoomId,
                startDate, endDate, restricted, includeApplications, cjscppuid, ACCEPT_COURTLIST_PAYLOAD, COURT_LIST_PAYLOAD_PATH);
    }

    private String fetchCourtListPayloadFromListing(
            CourtListType listId, String courtCentreId, String courtRoomId,
            String startDate, String endDate, boolean restricted, boolean includeApplications,
            String cjscppuid, String acceptHeader, String path) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(courtListDataBaseUrl).path(path)
                .queryParam("listId", listId.name())
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate)
                .queryParam("endDate", endDate)
                .queryParam("restricted", restricted)
                .queryParam("includeApplications", includeApplications);
        if (courtRoomId != null && !courtRoomId.isBlank()) {
            builder.queryParam("courtRoomId", courtRoomId);
        }
        String url = builder.build().toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.parseMediaType(acceptHeader)));
        if (cjscppuid != null && !cjscppuid.isBlank()) {
            headers.set(AppConstant.CJSCPPUID, cjscppuid);
        }

        try {
            ResponseEntity<String> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new CourtListDownloadException("Court list payload API returned empty response");
            }
            return body;
        } catch (RestClientException e) {
            log.error("Court list payload API call failed for listId={}, courtCentreId={}", listId, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list payload: " + e.getMessage(), e);
        }
    }
}

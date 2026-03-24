package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadException;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListFileResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class CourtListDataService {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();
    private static final String LIST_ID_PUBLIC = "PUBLIC";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String COURT_LIST_DATA_PATH = "courtlist";
    private static final String ACCEPT_COURTLIST_SEARCH = "application/vnd.courtlist.search.court.list+json";
    private static final Set<CourtListType> DOWNLOAD_TYPES_COURTLIST_API = EnumSet.of(
            CourtListType.PUBLIC,
            CourtListType.BENCH,
            CourtListType.ALPHABETICAL,
            CourtListType.USHERS_CROWN,
            CourtListType.USHERS_MAGISTRATE);

    private static final String CONTENT_TYPE_WORD =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String WORD_FILENAME = "CourtList.docx";
    private static final String DATA_URL_PREFIX = "data:";
    private static final String DATA_URL_BASE64 = "base64,";

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
        String json = progressionQueryService.getCourtListPayload(
                listId, courtCentreId, courtRoomId, startDate, endDate, restricted, currentUserId, includeApplications);
        return json != null ? json : "{}";
    }

    public CourtListPayload getCourtListPayload(
            CourtListType listId,
            String courtCentreId,
            String startDate,
            String endDate,
            String cjscppuid,
            boolean includeApplications) {
        boolean restricted = cjscppuid != null && !cjscppuid.trim().isEmpty();
        String json = getCourtListData(listId, courtCentreId, null, startDate, endDate, restricted, cjscppuid, includeApplications);

        try {
            return OBJECT_MAPPER.readValue(json, CourtListPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize court list data to CourtListPayload: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to parse court list payload", e);
        }
    }

    public Map<String, Object> getCourtListPayloadFromCourtListApi(
            String listId, String courtCentreId, LocalDate startDate, LocalDate endDate) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }
        String url = UriComponentsBuilder.fromUriString(courtListDataBaseUrl + "/" + COURT_LIST_DATA_PATH)
                .queryParam("listId", listId)
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate.format(DATE_FORMAT))
                .queryParam("endDate", endDate.format(DATE_FORMAT))
                .queryParam("restricted", false)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(
                MediaType.parseMediaType(ACCEPT_COURTLIST_SEARCH)));

        try {
            ResponseEntity<Map<String, Object>> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Court list API call failed for listId={}, courtCentreId={}", listId, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getPublicCourtListPayload(String courtCentreId, LocalDate startDate, LocalDate endDate) {
        return getCourtListPayloadFromCourtListApi(LIST_ID_PUBLIC, courtCentreId, startDate, endDate);
    }

    public Map<String, Object> getCourtListPayloadForDownload(
            CourtListType courtListType, String courtCentreId, LocalDate startDate, LocalDate endDate) {
        if (!DOWNLOAD_TYPES_COURTLIST_API.contains(courtListType)) {
            throw new CourtListDownloadException("Unsupported court list type for download: " + courtListType);
        }
        return getCourtListPayloadFromCourtListApi(courtListType.name(), courtCentreId, startDate, endDate);
    }

    public CourtListFileResult getCourtListFileForDownload(
            CourtListType courtListType, String courtCentreId, LocalDate startDate, LocalDate endDate) {
        if (courtListDataBaseUrl.isBlank()) {
            throw new CourtListDownloadException("Court list data is not configured");
        }
        String url = UriComponentsBuilder.fromUriString(courtListDataBaseUrl + "/" + COURT_LIST_DATA_PATH)
                .queryParam("listId", courtListType.name())
                .queryParam("courtCentreId", courtCentreId)
                .queryParam("startDate", startDate.format(DATE_FORMAT))
                .queryParam("endDate", endDate.format(DATE_FORMAT))
                .queryParam("restricted", false)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.parseMediaType(ACCEPT_COURTLIST_SEARCH)));

        try {
            ResponseEntity<String> response = publicCourtListRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new CourtListDownloadException("Court list API returned empty response");
            }
            byte[] content;
            if (body.startsWith(DATA_URL_PREFIX) && body.contains(DATA_URL_BASE64)) {
                int i = body.indexOf(DATA_URL_BASE64);
                String base64 = body.substring(i + DATA_URL_BASE64.length()).trim();
                content = Base64.getDecoder().decode(base64);
            } else {
                content = body.getBytes(StandardCharsets.UTF_8);
            }
            if (content.length == 0) {
                throw new CourtListDownloadException("Court list API returned empty file");
            }
            return new CourtListFileResult(content, CONTENT_TYPE_WORD, WORD_FILENAME);
        } catch (RestClientException e) {
            log.error("Court list file API call failed for listId={}, courtCentreId={}", courtListType, courtCentreId, e);
            throw new CourtListDownloadException("Failed to fetch court list file: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Court list file base64 decode failed for listId={}", courtListType, e);
            throw new CourtListDownloadException("Invalid court list file response: " + e.getMessage(), e);
        }
    }
}

package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaTHService {

    record CathHeaderInfo(String cathCourtListType, String sensitivity){}

    private static final String CATH_BLOB_SUFFIX = "-cath.json";

    private static final Map<CourtListType, CathHeaderInfo> COURT_LIST_MAPPINGS = ImmutableMap.of(
            CourtListType.ONLINE_PUBLIC, new CathHeaderInfo("MAGISTRATES_PUBLIC_LIST", "PUBLIC"),
            CourtListType.STANDARD, new CathHeaderInfo("MAGISTRATES_STANDARD_LIST", "CLASSIFIED"));

    @Autowired
    private final CourtListPublisher caTHPublisher;

    private final Optional<AzureBlobService> azureBlobService;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    public void sendCourtListToCaTH(CourtListDocument courtListDocument, final CourtListType courtListType, final LocalDate publishDate,
                                    String courtIdNumeric, Boolean isWelsh, UUID courtListId) {
        try {
            log.info("Sending court list document to CaTH endpoint");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            final String courtIdFromRefData = courtIdNumeric != null && !courtIdNumeric.isBlank()
                    ? courtIdNumeric
                    : "0";

            final CathHeaderInfo cathListInfo = COURT_LIST_MAPPINGS.get(courtListType);

            if(cathListInfo == null) {
                throw new IllegalStateException("Unsupported court list type "+courtListType);
            }

            final Instant now = Instant.now();
            final String language = Boolean.TRUE.equals(isWelsh) ? "WELSH" : "ENGLISH";
            final DtsMeta dtsMeta = DtsMeta.builder()
                    .provenance("COMMON_PLATFORM")
                    .type("LIST")
                    .listType(cathListInfo.cathCourtListType())
                    .courtId(courtIdFromRefData)
                    .contentDate(StandardCourtListTransformationService.toIsoDateTimeOrNull(publishDate.toString()))
                    .language(language)
                    .sensitivity(cathListInfo.sensitivity())
                    .displayFrom(now.toString())
                    .displayTo(getDisplayTo(publishDate))
                    .build();

            final String payload = OBJECT_MAPPER.writeValueAsString(courtListDocument);

            uploadPayloadToBlob(payload, courtListId);

            final Integer res = caTHPublisher.publish(payload, dtsMeta);

            log.info("Successfully sent court list document to CaTH. Response status: {}", res);
        } catch (Exception e) {
            log.error("Error sending court list document to CaTH endpoint: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send court list document to CaTH: " + e.getMessage(), e);
        }
    }

    private void uploadPayloadToBlob(String payload, UUID courtListId) {
        azureBlobService.ifPresentOrElse(
            blobService -> {
                try {
                    String blobName = buildBlobName(courtListId);
                    blobService.uploadJson(payload, blobName);
                } catch (Exception e) {
                    log.error("Failed to upload CaTH payload to blob storage, continuing with publish", e);
                }
            },
            () -> log.debug("Azure Blob Service not available, skipping payload upload")
        );
    }

    public static String buildBlobName(UUID courtListId) {
        return courtListId + CATH_BLOB_SUFFIX;
    }

    static @NotNull String getDisplayTo(final LocalDate publishDate) {
        return publishDate.plusDays(1).atStartOfDay().minusMinutes(1).toInstant(ZoneOffset.UTC).toString();
    }
}

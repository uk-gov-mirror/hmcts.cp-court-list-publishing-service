package uk.gov.hmcts.cp.services.sjp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.hmcts.cp.cleanup.CleanupJobService;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.controllers.CourtListPublishController;
import uk.gov.hmcts.cp.services.CourtListPublisher;
import uk.gov.hmcts.cp.services.CourtListPublishStatusService;
import uk.gov.hmcts.cp.services.CourtListTaskTriggerService;
import uk.gov.hmcts.cp.services.JsonSchemaValidatorService;
import uk.gov.hmcts.cp.services.ReferenceDataService;
import uk.gov.hmcts.cp.services.courtlistdownload.CourtListDownloadService;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;
import uk.gov.hmcts.cp.services.sanitization.HtmlStrippingSanitizer;
import uk.gov.hmcts.cp.services.sanitization.RequiredStringFieldsRegistry;
import uk.gov.hmcts.cp.services.sanitization.WafPatternSanitizer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the SJP → CaTH publish flow when CATH_PUBLISHING_ENABLED=false.
 *
 * Wires the real SjpCourtListPublishService with cathPublishingEnabled=false so the
 * feature flag is exercised through the full HTTP stack (controller → service → guard).
 * CourtListPublisher is mocked to confirm it is never called when publishing is disabled.
 */
@ExtendWith(MockitoExtension.class)
class SjpCathPublishingDisabledIntegrationTest {

    private static final MediaType SJP_CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.courtlistpublishing-service.sjp.post+json");
    private static final String SJP_PUBLISH_URL = "/api/court-list-publish/sjp/publishCourtList";

    @Mock private CourtListPublisher courtListPublisher;
    @Mock private JsonSchemaValidatorService jsonSchemaValidatorService;
    @Mock private CourtListPublishStatusService service;
    @Mock private CourtListTaskTriggerService courtListTaskTriggerService;
    @Mock private CourtListDownloadService courtListDownloadService;
    @Mock private CleanupJobService cleanupJobService;
    @Mock private ReferenceDataService referenceDataService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @BeforeEach
    void setUp() {
        DocumentSanitizer sanitizer = new DocumentSanitizer(
                new WafPatternSanitizer("..\\.\\,../"),
                new HtmlStrippingSanitizer(),
                new RequiredStringFieldsRegistry());

        SjpCourtListPublishService sjpService = new SjpCourtListPublishService(
                new SjpToCathPayloadTransformer(),
                courtListPublisher,
                sanitizer,
                jsonSchemaValidatorService,
                false  // CATH_PUBLISHING_ENABLED=false
        );

        CourtListPublishController controller = new CourtListPublishController(
                service,
                courtListTaskTriggerService,
                courtListDownloadService,
                cleanupJobService,
                sjpService,
                referenceDataService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void publishSjpCourtList_returnsAcceptedWithDisabledMessage_whenCathPublishingDisabled() throws Exception {
        String requestJson = """
                {
                  "listType": "SJP_PUBLIC_LIST",
                  "listPayload": {
                    "generatedDateAndTime": "2025-03-09T10:00:00",
                    "readyCases": [
                      {
                        "caseUrn": "URN001",
                        "defendantName": "John Smith",
                        "prosecutorName": "CPS",
                        "sjpOffences": [{"title": "Speeding", "wording": "Drove at 90mph in a 70 zone"}]
                      }
                    ]
                  }
                }
                """;

        mockMvc.perform(post(SJP_PUBLISH_URL)
                        .contentType(SJP_CONTENT_TYPE)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.listType").value("SJP_PUBLIC_LIST"))
                .andExpect(jsonPath("$.message").value("CaTH publishing is disabled"));

        verify(courtListPublisher, never()).publish(anyString(), any());
    }

    @Test
    void publishSjpPressCourtList_returnsAcceptedWithDisabledMessage_whenCathPublishingDisabled() throws Exception {
        String requestJson = """
                {
                  "listType": "SJP_PRESS_LIST",
                  "listPayload": {
                    "generatedDateAndTime": "2025-03-09T10:00:00",
                    "readyCases": [
                      {
                        "caseUrn": "URN002",
                        "defendantName": "Jane Doe",
                        "prosecutorName": "CPS",
                        "sjpOffences": [{"title": "Littering", "wording": "Dropped litter in public"}]
                      }
                    ]
                  }
                }
                """;

        mockMvc.perform(post(SJP_PUBLISH_URL)
                        .contentType(SJP_CONTENT_TYPE)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.listType").value("SJP_PRESS_LIST"))
                .andExpect(jsonPath("$.message").value("CaTH publishing is disabled"));

        verify(courtListPublisher, never()).publish(anyString(), any());
    }
}

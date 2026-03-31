package uk.gov.hmcts.cp.http;

import static uk.gov.hmcts.cp.openapi.model.CourtListType.STANDARD;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * STANDARD: CaTH publish, PDF generation, PDF bytes vs expected file.
 * Clears {@code court_list_publish_status} and jobs before each test.
 */
class CourtListStandardPdfContentIntegrationTest extends CourtListIntegrationTestBase {

    private static final String EXPECTED_PDF_TXT = "wiremock/__files/expected-standard-pdf-content.txt";

    @BeforeEach
    void clearTablesBeforeTest() {
        clearTables();
    }

    @Test
    void publishCourtList_shouldPublishToCaTHAndGeneratePdfForSTANDARD_andPdfTextMatchesExpectedContent() throws Exception {
        String expectedContent = loadExpectedText(EXPECTED_PDF_TXT);
        DocumentGeneratorStub.stubDocumentCreate(expectedContent);
        try {
            UUID courtListId = publishCourtListExpectingRequestedInDb(STANDARD.toString());
            awaitSuccessfulPdfAndAssertDb(courtListId, STANDARD.toString());
            assertDownloadedPdfMatchesExpectedText(courtListId, expectedContent);
        } finally {
            AbstractTest.resetWireMock();
        }
    }
}

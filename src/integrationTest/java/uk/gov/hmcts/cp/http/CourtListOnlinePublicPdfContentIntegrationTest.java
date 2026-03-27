package uk.gov.hmcts.cp.http;

import static uk.gov.hmcts.cp.openapi.model.CourtListType.ONLINE_PUBLIC;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ONLINE_PUBLIC: CaTH publish, PDF generation, PDF bytes vs expected file.
 * Clears {@code court_list_publish_status} and jobs before each test.
 */
class CourtListOnlinePublicPdfContentIntegrationTest extends CourtListIntegrationTestBase {

    private static final String EXPECTED_PDF_TXT = "wiremock/__files/expected-online-public-pdf-content.txt";

    @BeforeEach
    void clearTablesBeforeTest() {
        clearTables();
    }

    @Test
    void publishCourtList_shouldPublishToCaTHAndGeneratePdfForONLINE_PUBLIC_andPdfTextMatchesExpectedContent() throws Exception {
        String expectedContent = loadExpectedText(EXPECTED_PDF_TXT);
        DocumentGeneratorStub.stubDocumentCreate(expectedContent);
        try {
            UUID courtListId = publishCourtListExpectingRequestedInDb(ONLINE_PUBLIC.toString());
            awaitSuccessfulPdfAndAssertDb(courtListId, ONLINE_PUBLIC.toString());
            assertDownloadedPdfMatchesExpectedText(courtListId, expectedContent);
        } finally {
            AbstractTest.resetWireMock();
        }
    }
}

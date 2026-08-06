package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.services.PublicationSchema;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.models.transformed.schema.AddressSchema;
import uk.gov.hmcts.cp.models.transformed.schema.DocumentSchema;
import uk.gov.hmcts.cp.models.transformed.schema.Venue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaValidatorServiceTest {


    private JsonSchemaValidatorService validatorService;
    private final ObjectMapper objectMapper = ObjectMapperConfig.getObjectMapper();

    @BeforeEach
    void setUp() {
        validatorService = new JsonSchemaValidatorService();
    }

    @Test
    void validate_shouldPassForValidDocument() {
        // Given - a valid CourtListDocument with all required fields
        CourtListDocument document = createMinimalValidDocument();

        // When/Then - should not throw exception
        assertThatCode(() -> validatorService.validate(document, PublicationSchema.STANDARD))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_shouldFailForDocumentWithInvalidDateFormat() throws Exception {
        // Given - a document with invalid date format
        String invalidJson = """
                {
                  "document": {
                    "publicationDate": "invalid-date-format"
                  },
                  "venue": {
                    "venueAddress": {
                      "line": ["Test Address"]
                    }
                  },
                  "courtLists": []
                }
                """;
        CourtListDocument document = objectMapper.readValue(invalidJson, CourtListDocument.class);

        // When/Then - should throw SchemaValidationException
        assertThatThrownBy(() -> validatorService.validate(document, PublicationSchema.STANDARD))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("JSON schema validation failed");
    }

    @Test
    void validate_shouldFailForDocumentMissingRequiredFields() throws Exception {
        // Given - a document missing required fields (document, venue, courtLists)
        String invalidJson = """
                {
                  "document": {
                    "publicationDate": "2024-01-01T12:00:00.000Z"
                  }
                }
                """;
        CourtListDocument document = objectMapper.readValue(invalidJson, CourtListDocument.class);

        // When/Then - should throw SchemaValidationException
        assertThatThrownBy(() -> validatorService.validate(document, PublicationSchema.STANDARD))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("JSON schema validation failed");
    }

    @Test
    void validate_shouldHandleNullDocument() {
        // When/Then - should throw SchemaValidationException for null document
        assertThatThrownBy(() -> validatorService.validate((CourtListDocument) null, PublicationSchema.STANDARD))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("Document cannot be null");
    }

    @Test
    void validate_shouldPassForFullPayloadFromStubData() throws Exception {
        // Given - load full payload from stub data
        CourtListPayload payload = loadPayloadFromStubData("stubdata/court-list-payload-standard.json");

        // Transform the payload to CourtListDocument using the transformation service
        // Note: Validation is done separately, so transformation service doesn't need validator
        StandardCourtListTransformationService transformationService = new StandardCourtListTransformationService();

        CourtListDocument document = transformationService.transform(payload);

        // When/Then - validate should pass without throwing exception
        assertThatCode(() -> validatorService.validate(document, PublicationSchema.STANDARD))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_shouldPassForPublicCourtListPayloadFromStubData() throws Exception {
        // Given - load public court list payload from stub data
        CourtListPayload payload = loadPayloadFromStubData("stubdata/court-list-payload-public.json");

        // Transform the payload to CourtListDocument using the transformation service
        // Note: Validation is done separately, so transformation service doesn't need validator
        OnlinePublicCourtListTransformationService transformationService = new OnlinePublicCourtListTransformationService();

        CourtListDocument document = transformationService.transform(payload);

        // When/Then - validate should pass without throwing exception
        JsonSchemaValidatorService publicValidator = new JsonSchemaValidatorService();
        assertThatCode(() -> publicValidator.validate(document, PublicationSchema.ONLINE_PUBLIC))
                .doesNotThrowAnyException();
    }

    /**
     * Loads CourtListPayload from a stub data JSON file
     */
    private CourtListPayload loadPayloadFromStubData(String resourcePath) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String json = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        return objectMapper.readValue(json, CourtListPayload.class);
    }

    /**
     * Creates a minimal valid CourtListDocument for testing
     */
    private CourtListDocument createMinimalValidDocument() {
        DocumentSchema document = DocumentSchema.builder()
                .publicationDate("2024-01-01T12:00:00.000Z")
                .build();

        // Address must have at least one line to be valid
        List<String> addressLines = new ArrayList<>();
        addressLines.add("Test Address Line 1");

        AddressSchema venueAddress = AddressSchema.builder()
                .line(addressLines)
                .build();

        Venue venue = Venue.builder()
                .venueAddress(venueAddress)
                .build();

        return CourtListDocument.builder()
                .document(document)
                .venue(venue)
                .courtLists(new ArrayList<>())
                .build();
    }
}

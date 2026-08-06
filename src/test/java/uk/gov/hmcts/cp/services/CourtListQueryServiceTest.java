package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.models.CourtListPayload;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.services.sanitization.DocumentSanitizer;
import uk.gov.hmcts.cp.services.PublicationSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourtListQueryServiceTest {

    @Mock
    private CourtListDataService courtListDataService;

    @Mock
    private StandardCourtListTransformationService transformationService;

    @Mock
    private OnlinePublicCourtListTransformationService onlinePublicCourtListTransformationService;

    @Mock
    private JsonSchemaValidatorService jsonSchemaValidatorService;

    @Mock
    private DocumentSanitizer courtListDocumentSanitizer;

    @InjectMocks
    private CourtListQueryService courtListQueryService;

    private CourtListPayload payload;
    private CourtListDocument standardDocument;
    private CourtListDocument onlinePublicDocument;

    @BeforeEach
    void setUp() {
        payload = CourtListPayload.builder()
                .courtCentreName("Test Court")
                .build();

        standardDocument = CourtListDocument.builder().build();
        onlinePublicDocument = CourtListDocument.builder().build();
    }

    @Test
    void buildCourtListDocumentFromPayload_shouldUseStandardTransformation_whenListIdIsStandard() {
        // Given
        when(transformationService.transform(payload)).thenReturn(standardDocument);
        when(courtListDocumentSanitizer.sanitize(standardDocument)).thenReturn(standardDocument);
        doNothing().when(jsonSchemaValidatorService).validate(standardDocument, PublicationSchema.STANDARD);

        // When
        CourtListDocument result = courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.STANDARD);

        // Then
        assertThat(result).isEqualTo(standardDocument);
        verify(transformationService).transform(payload);
        verify(onlinePublicCourtListTransformationService, never()).transform(any());
        verify(courtListDocumentSanitizer).sanitize(standardDocument);
        verify(jsonSchemaValidatorService).validate(standardDocument, PublicationSchema.STANDARD);
    }

    @Test
    void buildCourtListDocumentFromPayload_shouldUsePublicTransformation_whenListIdIsPublic() {
        // Given
        when(onlinePublicCourtListTransformationService.transform(payload)).thenReturn(onlinePublicDocument);
        when(courtListDocumentSanitizer.sanitize(onlinePublicDocument)).thenReturn(onlinePublicDocument);
        doNothing().when(jsonSchemaValidatorService).validate(onlinePublicDocument, PublicationSchema.ONLINE_PUBLIC);

        // When
        CourtListDocument result = courtListQueryService.buildCourtListDocumentFromPayload(payload, CourtListType.ONLINE_PUBLIC);

        // Then
        assertThat(result).isEqualTo(onlinePublicDocument);
        verify(onlinePublicCourtListTransformationService).transform(payload);
        verify(transformationService, never()).transform(any());
        verify(courtListDocumentSanitizer).sanitize(onlinePublicDocument);
        verify(jsonSchemaValidatorService).validate(onlinePublicDocument, PublicationSchema.ONLINE_PUBLIC);
    }

    @Test
    void getCourtListPayload_shouldReturnPayloadFromCourtListDataService() {
        // Given - CourtListDataService returns payload (already enriched with reference data)
        when(courtListDataService.getCourtListPayload(CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", "cjscppuid", false))
                .thenReturn(payload);

        // When
        CourtListPayload result = courtListQueryService.getCourtListPayload(
                CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", "cjscppuid", false);

        // Then
        assertThat(result).isEqualTo(payload);
        verify(courtListDataService).getCourtListPayload(CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", "cjscppuid", false);
    }

    @Test
    void getCourtListPayload_shouldReturnPayloadWhenNoCjscppuid() {
        // Given
        when(courtListDataService.getCourtListPayload(CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", null, false))
                .thenReturn(payload);

        // When
        CourtListPayload result = courtListQueryService.getCourtListPayload(
                CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", null, false);

        // Then
        assertThat(result).isEqualTo(payload);
        verify(courtListDataService).getCourtListPayload(CourtListType.STANDARD, "courtId", "2026-01-05", "2026-01-12", null, false);
    }

}

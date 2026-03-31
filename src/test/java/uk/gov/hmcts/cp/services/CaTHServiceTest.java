package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

@ExtendWith(MockitoExtension.class)
class CaTHServiceTest {

    @Mock
    private CourtListPublisher cathPublisher;

    @Mock
    private AzureBlobService azureBlobService;

    private CaTHService cathService;

    private CourtListDocument courtListDocument;

    private static final UUID COURT_LIST_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @BeforeEach
    void setUp() {
        cathService = new CaTHService(cathPublisher, Optional.of(azureBlobService));
        courtListDocument = CourtListDocument.builder().build();
    }

    @Test
    void sendCourtListToCaTH_shouldCallPublisherWithCorrectParameters() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        LocalDate publishDate = LocalDate.of(2024, 1, 15);
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, publishDate, null, null, COURT_LIST_ID);

        // Then - Verify CaTHPublisher was called
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        
        verify(cathPublisher).publish(payloadCaptor.capture(), metaCaptor.capture());

        // Verify payload contains the court list document
        String capturedPayload = payloadCaptor.getValue();
        assertThat(capturedPayload).isNotNull().isNotEmpty();

        // Verify metadata is set correctly (DtsMeta is built inside sendCourtListToCaTH; capture it via the mocked publisher)
        DtsMeta capturedMeta = metaCaptor.getValue();
        assertThat(capturedMeta).isNotNull();
        assertThat(capturedMeta.getProvenance()).isEqualTo("COMMON_PLATFORM");
        assertThat(capturedMeta.getType()).isEqualTo("LIST");
        assertThat(capturedMeta.getListType()).isEqualTo("MAGISTRATES_PUBLIC_LIST");
        assertThat(capturedMeta.getSensitivity()).isEqualTo("PUBLIC");
        // When document has no courtIdNumeric, DtsMeta uses fallback "0"
        assertThat(capturedMeta.getCourtId()).isEqualTo("0");
        assertThat(capturedMeta.getContentDate()).isEqualTo("2024-01-15T00:00:00.000Z");
        assertThat(capturedMeta.getDisplayTo()).isEqualTo("2024-01-15T23:59:00Z");
        // When isWelsh is null or false, language is ENGLISH
        assertThat(capturedMeta.getLanguage()).isEqualTo("ENGLISH");
        // displayFrom is Instant.now() at publish time — assert ISO-8601 instant shape, not an exact value
        assertThat(capturedMeta.getDisplayFrom())
                .matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    }

    @Test
    void sendCourtListToCaTH_shouldUseCourtIdNumericFromPayload_whenPresent() {
        // Given - courtIdNumeric passed from payload (reference data)
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, LocalDate.of(2024, 1, 15), "325", null, COURT_LIST_ID);

        // Then - DtsMeta uses courtId from payload
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(cathPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getCourtId()).isEqualTo("325");
    }

    @Test
    void sendCourtListToCaTH_shouldSetLanguageWelsh_whenIsWelshTrue() {
        // Given - isWelsh true passed from payload
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, LocalDate.of(2024, 1, 15), null, true, COURT_LIST_ID);

        // Then - DtsMeta has language WELSH
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(cathPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getLanguage()).isEqualTo("WELSH");
    }

    @Test
    void sendCourtListToCaTH_shouldSetLanguageEnglish_whenIsWelshFalseOrNull() {
        // Given - isWelsh false passed from payload
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, LocalDate.of(2024, 1, 15), null, false, COURT_LIST_ID);

        // Then - DtsMeta has language ENGLISH
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(cathPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getLanguage()).isEqualTo("ENGLISH");
    }

    @Test
    void sendCourtListToCaTH_shouldThrowException_whenPublisherThrowsException() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class)))
                .thenThrow(new RuntimeException("Publishing failed"));

        // When & Then
        assertThatThrownBy(() -> cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, LocalDate.of(2024, 1, 15), null, null, COURT_LIST_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to send court list document to CaTH");
    }

    @Test
    void sendCourtListToCaTH_shouldThrowException_whenGenericExceptionOccurs() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

        // When & Then
        assertThatThrownBy(() -> cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC, LocalDate.of(2024, 1, 15), null, null, COURT_LIST_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to send court list document to CaTH");
    }

    @Test
    void sendCourtListToCaTH_shouldMapStandardListType() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.STANDARD,
            LocalDate.of(2024, 1, 15), "100", null, COURT_LIST_ID);

        // Then
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(cathPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getListType()).isEqualTo("MAGISTRATES_STANDARD_LIST");
        assertThat(metaCaptor.getValue().getSensitivity()).isEqualTo("CLASSIFIED");
    }

    @Test
    void sendCourtListToCaTH_shouldThrowException_whenCourtListTypeNotSupported() {
        // When & Then
        assertThatThrownBy(() -> cathService.sendCourtListToCaTH(
                courtListDocument, CourtListType.PUBLIC, LocalDate.of(2024, 1, 15), null, null, COURT_LIST_ID))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to send court list document to CaTH")
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendCourtListToCaTH_shouldFallbackToZero_whenCourtIdNumericIsBlank() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC,
            LocalDate.of(2024, 1, 15), "  ", null, COURT_LIST_ID);

        // Then
        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(cathPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getCourtId()).isEqualTo("0");
    }

    @Test
    void sendCourtListToCaTH_shouldUploadPayloadToBlobBeforePublishing() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC,
            LocalDate.of(2024, 1, 15), "325", null, COURT_LIST_ID);

        // Then - blob upload called before publish (order verified)
        InOrder inOrder = inOrder(azureBlobService, cathPublisher);
        inOrder.verify(azureBlobService).uploadJson(anyString(), anyString());
        inOrder.verify(cathPublisher).publish(anyString(), any(DtsMeta.class));
    }

    @Test
    void sendCourtListToCaTH_shouldUploadSamePayloadToBlobAndPublisher() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC,
            LocalDate.of(2024, 1, 15), "325", null, COURT_LIST_ID);

        // Then - same payload sent to both blob and publisher
        ArgumentCaptor<String> blobPayloadCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> publishPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureBlobService).uploadJson(blobPayloadCaptor.capture(), anyString());
        verify(cathPublisher).publish(publishPayloadCaptor.capture(), any(DtsMeta.class));
        assertThat(blobPayloadCaptor.getValue()).isEqualTo(publishPayloadCaptor.getValue());
    }

    @Test
    void sendCourtListToCaTH_shouldUploadBlobWithCorrectName() {
        // Given
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.STANDARD,
            LocalDate.of(2024, 6, 20), "450", false, COURT_LIST_ID);

        // Then
        ArgumentCaptor<String> blobNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(azureBlobService).uploadJson(anyString(), blobNameCaptor.capture());
        String blobName = blobNameCaptor.getValue();
        assertThat(blobName).isEqualTo(COURT_LIST_ID + "-cath.json");
    }

    @Test
    void sendCourtListToCaTH_shouldContinuePublishing_whenBlobUploadFails() {
        // Given
        doThrow(new RuntimeException("Blob upload failed"))
            .when(azureBlobService).uploadJson(anyString(), anyString());
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        cathService.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC,
            LocalDate.of(2024, 1, 15), null, null, COURT_LIST_ID);

        // Then - publish still called despite blob failure
        verify(cathPublisher).publish(anyString(), any(DtsMeta.class));
    }

    @Test
    void sendCourtListToCaTH_shouldSkipBlobUpload_whenBlobServiceNotAvailable() {
        // Given - no blob service
        CaTHService serviceWithoutBlob = new CaTHService(cathPublisher, Optional.empty());
        when(cathPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(HttpStatus.OK.value());

        // When
        serviceWithoutBlob.sendCourtListToCaTH(courtListDocument, CourtListType.ONLINE_PUBLIC,
            LocalDate.of(2024, 1, 15), null, null, COURT_LIST_ID);

        // Then - publish called, blob service never invoked
        verify(azureBlobService, never()).uploadJson(anyString(), anyString());
        verify(cathPublisher).publish(anyString(), any(DtsMeta.class));
    }

    @Test
    void buildBlobName_shouldCreateCorrectNameFromCourtListId() {
        UUID id = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        String blobName = CaTHService.buildBlobName(id);

        assertThat(blobName).isEqualTo("a1b2c3d4-e5f6-7890-abcd-ef1234567890-cath.json");
    }

    @Test
    void testGetDisplayTo() {
        final LocalDate now = LocalDate.now();
        final String expected = CaTHService.getDisplayTo(now);
        assertThat(expected).isEqualTo(now + "T23:59:00Z");
    }
}

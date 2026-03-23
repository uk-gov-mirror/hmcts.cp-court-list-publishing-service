package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.domain.DtsMeta;
import uk.gov.hmcts.cp.domain.sjp.SjpListPayload;
import uk.gov.hmcts.cp.services.CourtListPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SjpCourtListPublishServiceTest {

    @Mock
    private CourtListPublisher courtListPublisher;

    private SjpCourtListPublishService service;

    @BeforeEach
    void setUp() {
        service = new SjpCourtListPublishService(new SjpToCathPayloadTransformer(), courtListPublisher);
    }

    @Test
    void publishSjpCourtList_usesCourtIdNumericOnDtsMeta_whenPresent() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.of(
                        "caseUrn", "URN1",
                        "defendantName", "D",
                        "prosecutorName", "P",
                        "sjpOffences", List.of(Map.of("title", "t", "wording", "w")))),
                "325");

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLISH_LIST, null, null, payload);

        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(courtListPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getCourtId()).isEqualTo("325");
    }

    @Test
    void publishSjpCourtList_fallsBackToZeroOnDtsMeta_whenCourtIdNumericBlank() {
        when(courtListPublisher.publish(anyString(), any(DtsMeta.class))).thenReturn(200);

        SjpListPayload payload = new SjpListPayload(
                "2025-03-09T10:00:00",
                List.of(Map.of(
                        "caseUrn", "URN1",
                        "defendantName", "D",
                        "prosecutorName", "P",
                        "sjpOffences", List.of(Map.of("title", "t", "wording", "w")))),
                "   ");

        service.publishSjpCourtList(SjpCourtListPublishService.SJP_PUBLISH_LIST, null, null, payload);

        ArgumentCaptor<DtsMeta> metaCaptor = ArgumentCaptor.forClass(DtsMeta.class);
        verify(courtListPublisher).publish(anyString(), metaCaptor.capture());
        assertThat(metaCaptor.getValue().getCourtId()).isEqualTo("0");
    }
}

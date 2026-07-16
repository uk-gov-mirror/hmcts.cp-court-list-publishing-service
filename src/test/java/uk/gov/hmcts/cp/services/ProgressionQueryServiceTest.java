package uk.gov.hmcts.cp.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.cp.openapi.model.CourtListType;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgressionQueryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ProgressionQueryService progressionQueryService;

    @BeforeEach
    void setUp() throws Exception {
        var field = ProgressionQueryService.class.getDeclaredField("baseUrl");
        field.setAccessible(true);
        field.set(progressionQueryService, "https://progression.example.com");
    }

    @Test
    void getCourtListPayload_callsProgressionCourtlistdataWithCorrectParams() {
        String expectedJson = "{\"listType\":\"standard\",\"courtCentreName\":\"Test\"}";
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(expectedJson, HttpStatus.OK));

        String result = progressionQueryService.getCourtListPayload(
                CourtListType.STANDARD,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                "test-cjscppuid",
                false);

        assertThat(result).isEqualTo(expectedJson);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void getCourtListPayload_usesPrisonAcceptHeaderAndOmitsListIdForPrison() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        progressionQueryService.getCourtListPayload(
                CourtListType.PRISON,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                "test-cjscppuid",
                false);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));

        assertThat(entityCaptor.getValue().getHeaders().getFirst("Accept"))
                .isEqualTo("application/vnd.progression.search.prison.court.list.data+json");
        assertThat(uriCaptor.getValue().getQuery()).doesNotContain("listId");
    }

    @Test
    void getCourtListPayload_usesStandardAcceptHeaderAndIncludesListIdForNonPrison() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        progressionQueryService.getCourtListPayload(
                CourtListType.STANDARD,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                "test-cjscppuid",
                false);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(uriCaptor.capture(), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));

        assertThat(entityCaptor.getValue().getHeaders().getFirst("Accept"))
                .isEqualTo("application/vnd.progression.search.court.list.data+json");
        assertThat(uriCaptor.getValue().getQuery()).contains("listId=STANDARD");
    }

    @Test
    void getCourtListPayload_throwsWhenBaseUrlNotConfigured() throws Exception {
        var field = ProgressionQueryService.class.getDeclaredField("baseUrl");
        field.setAccessible(true);
        field.set(progressionQueryService, "");

        assertThatThrownBy(() -> progressionQueryService.getCourtListPayload(
                CourtListType.STANDARD,
                "f8254db1-1683-483e-afb3-b87fde5a0a26",
                null,
                "2024-01-15",
                "2024-01-15",
                false,
                null,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url is not configured");
    }
}

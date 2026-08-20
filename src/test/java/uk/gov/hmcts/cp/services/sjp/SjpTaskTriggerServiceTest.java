package uk.gov.hmcts.cp.services.sjp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.openapi.model.SjpListType;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SjpTaskTriggerServiceTest {

    @Mock
    private ExecutionService executionService;

    private SjpTaskTriggerService service;

    private final UUID sjpListId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SjpTaskTriggerService(executionService);
    }

    @Test
    void triggerSjpPublishTask_submitsJobWithExpectedData() {
        service.triggerSjpPublishTask(
                sjpListId, "325", SjpListType.SJP_PUBLIC_LIST,
                LocalDate.of(2025, 3, 9), "WELSH", "FULL", "{\"generatedDateAndTime\":\"2025-03-09T10:00:00\"}");

        ArgumentCaptor<ExecutionInfo> captor = ArgumentCaptor.forClass(ExecutionInfo.class);
        verify(executionService).executeWith(captor.capture());

        ExecutionInfo submitted = captor.getValue();
        assertThat(submitted.getAssignedTaskName()).isEqualTo("SJP_PUBLISH_TASK");
        assertThat(submitted.getJobData().getString("sjpListId")).isEqualTo(sjpListId.toString());
        assertThat(submitted.getJobData().getString("sjpCourtIdNumeric")).isEqualTo("325");
        assertThat(submitted.getJobData().getString("sjpListType")).isEqualTo(SjpListType.SJP_PUBLIC_LIST.getValue());
        assertThat(submitted.getJobData().getString("sjpPublishDate")).isEqualTo("2025-03-09");
        assertThat(submitted.getJobData().getString("sjpLanguage")).isEqualTo("WELSH");
        assertThat(submitted.getJobData().getString("sjpRequestType")).isEqualTo("FULL");
        assertThat(submitted.getJobData().getString("sjpPayload")).contains("2025-03-09T10:00:00");
    }

    @Test
    void triggerSjpPublishTask_omitsOptionalFields_whenLanguageAndRequestTypeNull() {
        service.triggerSjpPublishTask(
                sjpListId, "0", SjpListType.SJP_PRESS_LIST,
                LocalDate.of(2025, 3, 9), null, null, "{}");

        ArgumentCaptor<ExecutionInfo> captor = ArgumentCaptor.forClass(ExecutionInfo.class);
        verify(executionService).executeWith(captor.capture());

        var jobData = captor.getValue().getJobData();
        assertThat(jobData.containsKey("sjpLanguage")).isFalse();
        assertThat(jobData.containsKey("sjpRequestType")).isFalse();
    }

    @Test
    void triggerSjpPublishTask_wrapsAndRethrows_whenExecutionServiceThrows() {
        doThrow(new RuntimeException("db unavailable")).when(executionService).executeWith(any());

        assertThatThrownBy(() -> service.triggerSjpPublishTask(
                sjpListId, "0", SjpListType.SJP_PUBLIC_LIST,
                LocalDate.of(2025, 3, 9), null, null, "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to trigger SJP publish task");
    }
}

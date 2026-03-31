package uk.gov.hmcts.cp.acl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class PermissionConstantsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void accessToPublishCourtListPermissions_returnsSingleJsonString() throws Exception {
        String[] permissions = PermissionConstants.accessToPublishCourtListPermissions();

        assertThat(permissions).hasSize(1);
        assertThat(permissions[0]).isNotBlank();

        JsonNode node = objectMapper.readTree(permissions[0]);
        assertThat(node.isObject()).isTrue();
        List<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder("object", "action");
    }

    @Test
    void accessToPublishCourtListPermissions_containsExpectedObjectAndAction() throws Exception {
        String[] permissions = PermissionConstants.accessToPublishCourtListPermissions();
        JsonNode node = objectMapper.readTree(permissions[0]);

        assertThat(node.path("object").asText()).isEqualTo("Court List");
        assertThat(node.path("action").asText()).isEqualTo("Publish");
    }
}

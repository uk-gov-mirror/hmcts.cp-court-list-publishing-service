package uk.gov.hmcts.cp.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieSession;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.moj.cpp.authz.drools.Action;
import uk.gov.moj.cpp.authz.drools.Outcome;
import uk.gov.moj.cpp.authz.http.providers.UserAndGroupProvider;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class CourtListPublishingAccessControlTest {

    private static final String PUBLISH_POST = "courtlistpublishing-service.publish.post";
    private static final String PUBLISH_GET = "courtlistpublishing-service.publish.get";
    private static final String FILES_DOWNLOAD = "courtlistpublishing-service.files.download";
    private static final String DOWNLOAD_GET = "courtlistpublishing-service.download.get";
    private static final String TEST_AUTH_POST = "courtlistpublishing-service.test-auth.post";
    private static final String PUBLIC_COURT_LIST_GET = "courtlistpublishing-service.public-court-list.get";
    private static final String PUBLISH_STATUS_CLEANUP = "courtlistpublishing-service.publish-status-cleanup";

    private static KieBase kieBase;

    @Mock
    private UserAndGroupProvider userAndGroupProvider;

    @BeforeAll
    static void compileRules() {
        KieServices ks = KieServices.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/acl/court-list-publishing-rules.drl",
                ks.getResources().newClassPathResource("acl/court-list-publishing-rules.drl"));

        KieBuilder kb = ks.newKieBuilder(kfs);
        kb.buildAll();

        assertThat(kb.getResults().getMessages(Message.Level.ERROR))
                .as("DRL compilation should produce no errors")
                .isEmpty();

        kieBase = ks.newKieContainer(kb.getKieModule().getReleaseId()).getKieBase();
    }

    private Outcome evaluateRule(Action action) {
        KieSession session = kieBase.newKieSession();
        try {
            session.setGlobal("userAndGroupProvider", userAndGroupProvider);
            Outcome outcome = new Outcome();
            session.insert(action);
            session.insert(outcome);
            session.fireAllRules();
            return outcome;
        } finally {
            session.dispose();
        }
    }

    // --- publish.post ---

    @Test
    void publishPost_shouldAllowAuthorisedUser() {
        Action action = new Action(PUBLISH_POST, Map.of());
        given(userAndGroupProvider.hasPermission(action,
                PermissionConstants.accessToPublishCourtListPermissions())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    // --- publish.get ---

    @Test
    void publishGet_shouldAllowAuthorisedUser() {
        Action action = new Action(PUBLISH_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void publishGet_shouldDenyUnauthorisedUser() {
        Action action = new Action(PUBLISH_GET, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- files.download ---

    @Test
    void filesDownload_shouldAllowAuthorisedUser() {
        Action action = new Action(FILES_DOWNLOAD, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void filesDownload_shouldDenyUnauthorisedUser() {
        Action action = new Action(FILES_DOWNLOAD, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- download.get ---

    @Test
    void downloadGet_shouldAllowAuthorisedUser() {
        Action action = new Action(DOWNLOAD_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void downloadGet_shouldDenyUnauthorisedUser() {
        Action action = new Action(DOWNLOAD_GET, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- prison download (dedicated action + media type, prison-list roles only) ---

    private static final String PRISON_DOWNLOAD_GET = "courtlistpublishing-service.prison-download.get";

    @Test
    void prisonDownload_shouldAllowPrisonListRole() {
        Action action = new Action(PRISON_DOWNLOAD_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPrisonListRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void prisonDownload_shouldDenyUserWithoutPrisonListRole() {
        Action action = new Action(PRISON_DOWNLOAD_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPrisonListRoles())).willReturn(false);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    @Test
    void prisonDownload_shouldNotBeGrantedByGenericDownloadRule() {
        // The prison action is not in the generic download rule's name list, so general publishing
        // membership must not grant it. Only getPrisonListRoles() can.
        Action action = new Action(PRISON_DOWNLOAD_GET, Map.of());
        lenient().when(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).thenReturn(true);
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPrisonListRoles())).willReturn(false);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- test-auth.post (rule removed under LPT-2389: was testing-only public access) ---

    @Test
    void testAuthPost_shouldBeDenied() {
        Action action = new Action(TEST_AUTH_POST, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- public-court-list.get ---

    @Test
    void publicCourtListGet_shouldAllowAuthorisedUser() {
        Action action = new Action(PUBLIC_COURT_LIST_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void publicCourtListGet_shouldDenyUnauthorisedUser() {
        Action action = new Action(PUBLIC_COURT_LIST_GET, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- publish-status-cleanup (System Users only) ---

    @Test
    void publishStatusCleanup_shouldAllowSystemUser() {
        Action action = new Action(PUBLISH_STATUS_CLEANUP, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getSystemUserOnlyRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void publishStatusCleanup_shouldDenyNonSystemUser() {
        Action action = new Action(PUBLISH_STATUS_CLEANUP, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getSystemUserOnlyRoles())).willReturn(false);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- unknown action should be denied ---

    @Test
    void unknownAction_shouldBeDenied() {
        Action action = new Action("courtlistpublishing-service.unknown.action", Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    @Test
    void publishPost_shouldDenyUnauthorisedUser() {
        Action action = new Action(PUBLISH_POST, Map.of());
        given(userAndGroupProvider.hasPermission(action,
                PermissionConstants.accessToPublishCourtListPermissions())).willReturn(false);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }
}

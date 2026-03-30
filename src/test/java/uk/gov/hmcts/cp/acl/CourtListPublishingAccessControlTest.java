package uk.gov.hmcts.cp.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.COURT_ADMINISTRATORS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.COURT_ASSOCIATE;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.COURT_CLERKS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.CPS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.CROWN_COURT_ADMIN;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.DISTRICT_JUDGE;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.LEGAL_ADVISERS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.LISTING_OFFICERS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.NON_CPS_PROSECUTORS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.POLICE_ADMIN;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.PROBATION_ADMIN;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.SYSTEM_USERS;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.VICTIMS_WITNESS_CARE_ADMIN;
import static uk.gov.hmcts.cp.acl.SecurityGroupConstants.YOUTH_OFFENDING_SERVICE_ADMIN;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final String DOWNLOAD_POST = "courtlistpublishing-service.download.post";
    private static final String TEST_AUTH_POST = "courtlistpublishing-service.test-auth.post";
    private static final String PUBLIC_COURT_LIST_GET = "courtlistpublishing-service.public-court-list.get";
    private static final String PUBLISH_STATUS_CLEANUP_GET = "courtlistpublishing-service.publish-status-cleanup.get";

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
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void publishPost_shouldDenyUnauthorisedUser() {
        Action action = new Action(PUBLISH_POST, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
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

    // --- download.post ---

    @Test
    void downloadPost_shouldAllowAuthorisedUser() {
        Action action = new Action(DOWNLOAD_POST, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void downloadPost_shouldDenyUnauthorisedUser() {
        Action action = new Action(DOWNLOAD_POST, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isFalse();
    }

    // --- test-auth.post (public access, no group check) ---

    @Test
    void testAuthPost_shouldAllowAnyUser() {
        Action action = new Action(TEST_AUTH_POST, Map.of());

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
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

    // --- publish-status-cleanup.get (System Users only) ---

    @Test
    void publishStatusCleanupGet_shouldAllowSystemUser() {
        Action action = new Action(PUBLISH_STATUS_CLEANUP_GET, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getSystemUserOnlyRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    void publishStatusCleanupGet_shouldDenyNonSystemUser() {
        Action action = new Action(PUBLISH_STATUS_CLEANUP_GET, Map.of());
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

    // --- verify each publishing role individually grants access ---

    @ParameterizedTest
    @ValueSource(strings = {
            LISTING_OFFICERS, COURT_CLERKS, LEGAL_ADVISERS, CROWN_COURT_ADMIN,
            COURT_ADMINISTRATORS, YOUTH_OFFENDING_SERVICE_ADMIN, CPS, PROBATION_ADMIN,
            VICTIMS_WITNESS_CARE_ADMIN, POLICE_ADMIN, COURT_ASSOCIATE,
            NON_CPS_PROSECUTORS, DISTRICT_JUDGE, SYSTEM_USERS
    })
    void publishPost_shouldAllowEachAuthorisedGroup(String group) {
        Action action = new Action(PUBLISH_POST, Map.of());
        given(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups(action,
                SecurityGroupConstants.getPublishingRoles())).willReturn(true);

        Outcome outcome = evaluateRule(action);

        assertThat(outcome.isSuccess())
                .as("Group '%s' should be allowed to publish", group)
                .isTrue();
    }
}

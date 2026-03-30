package uk.gov.hmcts.cp.acl;

/**
 * Security group constants used in Drools access-control rules.
 * Mirrors the role names defined in the usersgroups identity service.
 */
public final class SecurityGroupConstants {

    public static final String LISTING_OFFICERS = "Listing Officers";
    public static final String COURT_CLERKS = "Court Clerks";
    public static final String LEGAL_ADVISERS = "Legal Advisers";
    public static final String CROWN_COURT_ADMIN = "Crown Court Admin";
    public static final String COURT_ADMINISTRATORS = "Court Administrators";
    public static final String YOUTH_OFFENDING_SERVICE_ADMIN = "Youth Offending Service Admin";
    public static final String CPS = "CPS";
    public static final String PROBATION_ADMIN = "Probation Admin";
    public static final String VICTIMS_WITNESS_CARE_ADMIN = "Victims & Witness Care Admin";
    public static final String POLICE_ADMIN = "Police Admin";
    public static final String COURT_ASSOCIATE = "Court Associate";
    public static final String NON_CPS_PROSECUTORS = "Non CPS Prosecutors";
    public static final String DISTRICT_JUDGE = "District Judge";
    public static final String SYSTEM_USERS = "System Users";

    private SecurityGroupConstants() {
    }

    /**
     * Groups allowed to publish court lists (courtlistpublishing-service.publish.post).
     */
    public static String[] getPublishingRoles() {
        return new String[]{
                LISTING_OFFICERS, COURT_CLERKS, LEGAL_ADVISERS, CROWN_COURT_ADMIN,
                COURT_ADMINISTRATORS, YOUTH_OFFENDING_SERVICE_ADMIN, CPS, PROBATION_ADMIN,
                VICTIMS_WITNESS_CARE_ADMIN, POLICE_ADMIN, COURT_ASSOCIATE,
                NON_CPS_PROSECUTORS, DISTRICT_JUDGE, SYSTEM_USERS
        };
    }

    /**
     * Groups allowed for system-user-only endpoints (e.g. publish-status-cleanup).
     */
    public static String[] getSystemUserOnlyRoles() {
        return new String[]{ SYSTEM_USERS };
    }
}

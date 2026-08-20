package uk.gov.hmcts.cp.task;

/**
 * Constants for job data keys used when triggering and executing court list publish tasks.
 */
public final class JobDataConstant {

    public static final String COURT_LIST_ID = "courtListId";
    public static final String COURT_CENTRE_ID = "courtCentreId";
    public static final String COURT_LIST_TYPE = "courtListType";
    public static final String PUBLISH_DATE = "publishDate";
    public static final String USER_ID = "userId";

    public static final String SJP_LIST_ID = "sjpListId";
    public static final String SJP_COURT_ID_NUMERIC = "sjpCourtIdNumeric";
    public static final String SJP_LIST_TYPE = "sjpListType";
    public static final String SJP_PUBLISH_DATE = "sjpPublishDate";
    public static final String SJP_LANGUAGE = "sjpLanguage";
    public static final String SJP_REQUEST_TYPE = "sjpRequestType";
    public static final String SJP_PAYLOAD = "sjpPayload";

    private JobDataConstant() {
        // utility class
    }
}

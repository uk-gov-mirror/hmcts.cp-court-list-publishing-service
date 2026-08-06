package uk.gov.hmcts.cp.services;

public enum PublicationSchema {

    STANDARD("schema/standard-court-list-schema.json"),
    ONLINE_PUBLIC("schema/online-public-court-list-schema.json"),
    SJP_PUBLIC("schema/single-justice-procedure-public.json"),
    SJP_PRESS("schema/single-justice-procedure-press.json");

    private final String path;

    PublicationSchema(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}

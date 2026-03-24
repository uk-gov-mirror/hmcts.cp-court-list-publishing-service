package uk.gov.hmcts.cp.services.courtlistdownload;

public record CourtListFileResult(byte[] content, String contentType, String filename) {}

package uk.gov.hmcts.cp.domain.sjp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * SJP list payload (PubHub parity). Contains generatedDateAndTime and readyCases
 * as supplied by SJP (e.g. public.sjp.pending-cases-public-list-generated).
 * Optional {@code courtIdNumeric} aligns with {@link uk.gov.hmcts.cp.models.CourtListPayload}
 * for CaTH {@code DtsMeta.courtId} (reference-data numeric id, e.g. {@code "325"}).
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SjpListPayload {

    private final String generatedDateAndTime;
    private final List<Map<String, Object>> readyCases;
    private final String courtIdNumeric;

    public SjpListPayload(String generatedDateAndTime, List<Map<String, Object>> readyCases) {
        this(generatedDateAndTime, readyCases, null);
    }

    @JsonCreator
    public SjpListPayload(
            @JsonProperty("generatedDateAndTime") String generatedDateAndTime,
            @JsonProperty("readyCases") List<Map<String, Object>> readyCases,
            @JsonProperty("courtIdNumeric") String courtIdNumeric) {
        this.generatedDateAndTime = generatedDateAndTime;
        this.readyCases = readyCases;
        this.courtIdNumeric = courtIdNumeric;
    }
}

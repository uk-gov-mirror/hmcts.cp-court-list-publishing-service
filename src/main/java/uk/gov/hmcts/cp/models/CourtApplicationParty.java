package uk.gov.hmcts.cp.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourtApplicationParty {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("organisationName")
    private String organisationName;

    @JsonProperty("welshOrganisationName")
    private String welshOrganisationName;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("surname")
    private String surname;

    @JsonProperty("welshSurname")
    private String welshSurname;

    @JsonProperty("dateOfBirth")
    private String dateOfBirth;

    @JsonProperty("age")
    private String age;

    @JsonProperty("nationality")
    private String nationality;

    @JsonProperty("asn")
    private String asn;

    @JsonProperty("pncId")
    private String pncId;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("address")
    private Address address;

    @JsonProperty("reportingRestrictions")
    private List<ReportingRestriction> reportingRestrictions;

    @JsonProperty("offences")
    private List<Offence> offences;
}

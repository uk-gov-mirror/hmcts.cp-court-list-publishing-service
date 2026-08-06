package uk.gov.hmcts.cp.models.transformed.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndividualDetails {
    @JsonProperty("individualForenames")
    private String individualForenames;
    
    @JsonProperty("individualMiddleName")
    private String individualMiddleName;
    
    @JsonProperty("individualSurname")
    private String individualSurname;
    
    @JsonProperty("dateOfBirth")
    private String dateOfBirth;
    
    @JsonProperty("age")
    private Integer age;
    
    @JsonProperty("address")
    private AddressSchema address;
    
    @JsonProperty("inCustody")
    private Boolean inCustody;
    
    @JsonProperty("gender")
    private String gender;
    
    @JsonProperty("asn")
    private String asn;

    @JsonProperty("pncId")
    private String pncId;
}

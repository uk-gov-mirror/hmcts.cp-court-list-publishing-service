package uk.gov.hmcts.cp.services.sanitization;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;
import uk.gov.hmcts.cp.models.transformed.schema.AddressSchema;
import uk.gov.hmcts.cp.models.transformed.schema.Venue;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSanitizerTest {

    private final DocumentSanitizer sanitizer = new DocumentSanitizer(
            new WafPatternSanitizer("..\\,../"),
            new HtmlStrippingSanitizer(),
            new RequiredStringFieldsRegistry());

    @Test
    void returnsNullWhenDocumentIsNull() {
        assertThat(sanitizer.sanitize((CourtListDocument) null)).isNull();
    }

    @Test
    void stripsHtmlFromNestedStringFields() {
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("Manchester <br/> City")
                .county("Greater Manchester")
                .postCode("M1<br />1AA")
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEqualTo("Manchester City");
        assertThat(cleaned.getCounty()).isEqualTo("Greater Manchester");
        assertThat(cleaned.getPostCode()).isEqualTo("M1 1AA");
    }

    @Test
    void stripsHtmlFromStringListEntries() {
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .line(List.of(
                        "1 Court Street",
                        "Floor <br/> 2",
                        "Entity-encoded &lt;p&gt;wrapping&lt;/p&gt; text"))
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getLine()).containsExactly(
                "1 Court Street",
                "Floor 2",
                "Entity-encoded wrapping text");
    }

    @Test
    void preservesBareLessThanWhenNotTagShaped() {
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("Aged < 18 and < 20")
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEqualTo("Aged < 18 and < 20");
    }

    @Test
    void preservesRequiredFieldAsEmptyStringWhenSanitisationStripsValue() {
        // postCode is HTML-only after stripping. The walker must keep the field
        // present (as "") because postCode is declared as a required property
        // in online-public-court-list-schema.json — CaTH would otherwise fail
        // with "/venue/venueAddress: required property 'postCode' not found".
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("Manchester")
                .postCode("<br /><br />")
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEqualTo("Manchester");
        assertThat(cleaned.getPostCode()).isEmpty();
    }

    @Test
    void injectsEmptyStringForRequiredFieldEvenWhenUpstreamSuppliedNull() {
        // Same required-field protection but for the harder case: upstream
        // never set postCode at all (null in the POJO -> omitted from the
        // JsonNode tree by JsonInclude.NON_NULL -> sanitiser would never see
        // it). The required-field enforcement pass must inject "" here.
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("Manchester")
                // .postCode( ) deliberately omitted -> null
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEqualTo("Manchester");
        assertThat(cleaned.getPostCode()).isEmpty();
    }

    @Test
    void preservesOptionalFieldAsEmptyStringWhenSanitisationStripsValue() {
        // Even though town is optional, we no longer drop it when sanitisation
        // strips the value to empty — preserving every field that was supplied
        // keeps the JSON shape stable and avoids any chance of a "required
        // property not found" failure if a schema ever upgrades a field from
        // optional to required.
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("<br />")
                .postCode("M1 1AA")
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEmpty();
        assertThat(cleaned.getPostCode()).isEqualTo("M1 1AA");
    }

    @Test
    void replacesEmptyArrayEntryWithBlankToPreserveArrayLength() {
        // a list entry that is entirely HTML collapses to "" so callers can rely on
        // the original list size and index alignment
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .line(List.of("1 Court Street", "<br />", "Floor 2"))
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getLine()).containsExactly("1 Court Street", "", "Floor 2");
    }

    @Test
    void stripsWafBlockedPathTraversalSequences() {
        // Path-traversal sequences like ../ or ..\ would be rejected by the
        // Azure WAF if they appeared anywhere in the outbound JSON, even inside
        // legitimate free text. The walker must strip them via WafPatternSanitizer
        // before the document leaves this service.
        CourtListDocument input = documentWithAddress(AddressSchema.builder()
                .town("see ../docs for details")
                .county("backslash variant ..\\here")
                .build());

        AddressSchema cleaned = sanitizer.sanitize(input).getVenue().getVenueAddress();

        assertThat(cleaned.getTown()).isEqualTo("see docs for details");
        assertThat(cleaned.getCounty()).isEqualTo("backslash variant here");
    }

    @Test
    void leavesCleanDocumentUntouched() {
        AddressSchema original = AddressSchema.builder()
                .line(List.of("1 Court Street", "Floor 2"))
                .town("Manchester")
                .county("Greater Manchester")
                .postCode("M1 1AA")
                .build();
        CourtListDocument input = documentWithAddress(original);

        CourtListDocument cleaned = sanitizer.sanitize(input);

        assertThat(cleaned.getVenue().getVenueAddress())
                .usingRecursiveComparison()
                .isEqualTo(original);
    }

    private CourtListDocument documentWithAddress(AddressSchema address) {
        return CourtListDocument.builder()
                .venue(Venue.builder().venueAddress(address).build())
                .build();
    }
}

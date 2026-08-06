package uk.gov.hmcts.cp.services.sanitization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;

import java.util.Set;

/**
 * Walks a CourtListDocument as a Jackson tree and applies two passes to the
 * outgoing JSON:
 *
 * <ol>
 *   <li><b>Sanitisation.</b> Every textual leaf goes through
 *       {@link WafPatternSanitizer} (strips Azure-WAF-blocked substrings such
 *       as {@code ../} and {@code ..\}) and then {@link HtmlStrippingSanitizer}
 *       (strips HTML constructs and normalises whitespace). Fields are
 *       <strong>never</strong> dropped — if sanitisation strips the value to
 *       empty, the field is preserved as {@code ""} so CaTH's required-property
 *       checks continue to pass.</li>
 *   <li><b>Required-field enforcement.</b> The union of {@code required}
 *       string fields declared by
 *       {@code online-public-court-list-schema.json} and
 *       {@code standard-court-list-schema.json} is enforced after sanitisation
 *       — for each known parent object, any required string field that is
 *       <em>missing</em> or {@code null} is injected as {@code ""}. This
 *       narrowly handles the case where upstream supplied {@code null} at the
 *       POJO level (the field would otherwise be omitted from the tree by
 *       {@code JsonInclude.NON_NULL} before the walker can see it).</li>
 * </ol>
 *
 * <p>The set of required string fields is derived from the schemas at startup
 * by {@link RequiredStringFieldsRegistry}. Container fields (arrays, nested
 * objects) the schemas mark as required are out of scope — if upstream omits
 * a whole {@code venueAddress} or {@code courtRoom}, we can't fabricate
 * something meaningful, and the validation failure that follows is the
 * correct signal.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentSanitizer {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    private final WafPatternSanitizer wafPatternSanitizer;
    private final HtmlStrippingSanitizer stringSanitizer;
    private final RequiredStringFieldsRegistry requiredStringFieldsRegistry;

    public CourtListDocument sanitize(final CourtListDocument document) {
        if (document == null) {
            return null;
        }
        final JsonNode root = OBJECT_MAPPER.valueToTree(document);
        sanitiseTextNodes(root);
        enforceRequiredStringFields(root);
        return OBJECT_MAPPER.convertValue(root, CourtListDocument.class);
    }

    public String sanitize(final String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            return json;
        }
        final JsonNode root = OBJECT_MAPPER.readTree(json);
        sanitiseTextNodes(root);
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private String sanitizeText(final String original) {
        // WAF-blocked sequences (e.g. "../") are stripped first; the HTML
        // sanitiser then handles tag stripping plus whitespace normalisation
        // (so any gaps left by WAF substring removal are collapsed cleanly).
        final String wafCleaned = wafPatternSanitizer.sanitize(original);
        return stringSanitizer.sanitize(wafCleaned);
    }

    private void sanitiseTextNodes(final JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                final JsonNode child = objectNode.get(fieldName);
                if (child.isTextual()) {
                    final String original = child.asText();
                    final String cleaned = sanitizeText(original);
                    // Never drop a field. If sanitisation stripped everything,
                    // substitute "" so the field's presence (and CaTH's
                    // required-property checks) survive.
                    final String value = cleaned == null ? "" : cleaned;
                    if (!value.equals(original)) {
                        objectNode.put(fieldName, value);
                    }
                } else if (child.isContainerNode()) {
                    sanitiseTextNodes(child);
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                final JsonNode child = arrayNode.get(i);
                if (child.isTextual()) {
                    final String original = child.asText();
                    final String cleaned = sanitizeText(original);
                    // Keep array length stable: empty entries become "".
                    final String value = cleaned == null ? "" : cleaned;
                    if (!value.equals(original)) {
                        arrayNode.set(i, TextNode.valueOf(value));
                    }
                } else if (child.isContainerNode()) {
                    sanitiseTextNodes(child);
                }
            }
        }
    }

    private void enforceRequiredStringFields(final JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(fieldName -> {
                final JsonNode child = objectNode.get(fieldName);
                final Set<String> requiredOnChild =
                        requiredStringFieldsRegistry.requiredStringFieldsOf(fieldName);
                if (!requiredOnChild.isEmpty()) {
                    applyRequiredFields(child, requiredOnChild);
                }
                if (child.isContainerNode()) {
                    enforceRequiredStringFields(child);
                }
            });
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(item -> {
                if (item.isContainerNode()) {
                    enforceRequiredStringFields(item);
                }
            });
        }
    }

    private void applyRequiredFields(final JsonNode target, final Set<String> requiredFields) {
        if (target instanceof ObjectNode obj) {
            requiredFields.forEach(field -> {
                if (!obj.hasNonNull(field)) {
                    obj.put(field, "");
                }
            });
        } else if (target instanceof ArrayNode arr) {
            arr.forEach(item -> applyRequiredFields(item, requiredFields));
        }
    }
}

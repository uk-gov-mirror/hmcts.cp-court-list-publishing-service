package uk.gov.hmcts.cp.services.sanitization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds, at application startup, the union of {@code required} string-typed
 * fields declared by the CaTH court-list schemas. The result is keyed by the
 * <em>parent field name</em> in the JSON tree — i.e. the property whose value
 * is the object the rule applies to. Example: an entry of
 * {@code "venueAddress" -> {"postCode"}} means "wherever a tree node has a
 * field called {@code venueAddress}, the object value at that field must carry
 * a string {@code postCode}".
 *
 * <p>Replaces the previous hand-maintained map in
 * {@link DocumentSanitizer}. Adding a required string field to either
 * schema is now picked up automatically — no code change required.
 *
 * <p>Scope: only string-typed required fields are surfaced. Required
 * containers (arrays, nested objects) are out of scope — if upstream omits a
 * whole {@code venueAddress} or {@code courtRoom}, we can't fabricate
 * something meaningful, and the resulting schema-validation failure is the
 * correct signal.
 *
 * <p>JSON-Schema features handled: {@code properties}, {@code required},
 * {@code $ref} (including chained refs into {@code $defs}), and arrays via
 * {@code items}. Conditional or composed schemas
 * ({@code oneOf}/{@code allOf}/{@code anyOf}/{@code if-then-else}) are not
 * used by the current schemas and are intentionally not handled.
 */
@Component
@Slf4j
public class RequiredStringFieldsRegistry {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();

    private static final List<String> SCHEMA_RESOURCES = List.of(
            "schema/standard-court-list-schema.json",
            "schema/online-public-court-list-schema.json"
    );

    private final Map<String, Set<String>> requiredStringFieldsByParent;

    public RequiredStringFieldsRegistry() {
        final Map<String, Set<String>> merged = new HashMap<>();
        for (final String resource : SCHEMA_RESOURCES) {
            final JsonNode schema = loadSchema(resource);
            walk(schema, schema, merged);
        }
        this.requiredStringFieldsByParent = merged.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> Set.copyOf(e.getValue())));
        log.info("Loaded required-string-field registry: {} parent fields, {} required strings in total",
                requiredStringFieldsByParent.size(),
                requiredStringFieldsByParent.values().stream().mapToInt(Set::size).sum());
    }

    /**
     * @return the required string fields on the object that lives under the
     *     given parent field name, or an empty set if no rule applies.
     */
    public Set<String> requiredStringFieldsOf(final String parentFieldName) {
        return requiredStringFieldsByParent.getOrDefault(parentFieldName, Set.of());
    }

    /** Read-only view of the full registry, primarily for tests and diagnostics. */
    public Map<String, Set<String>> asMap() {
        return requiredStringFieldsByParent;
    }

    private JsonNode loadSchema(final String resource) {
        final ClassPathResource cp = new ClassPathResource(resource);
        if (!cp.exists()) {
            throw new IllegalStateException("Schema not found on classpath: " + resource);
        }
        try (InputStream in = cp.getInputStream()) {
            return OBJECT_MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load schema " + resource, e);
        }
    }

    /**
     * For each property in any {@code properties} block reachable from the
     * root, decide whether its value-object carries required string fields and
     * record the result against the property's name. Then descend into the
     * property's schema so nested properties are visited too.
     *
     * <p>Crucially, the literal property declaration is passed through (not
     * pre-resolved) — JSON Schema permits sibling keywords next to
     * {@code $ref} (e.g. a {@code required} array right next to a
     * {@code $ref}) and the sibling keywords would be silently dropped if we
     * resolved before extracting.
     */
    private void walk(final JsonNode node, final JsonNode root, final Map<String, Set<String>> out) {
        if (node == null || !node.isObject()) {
            return;
        }
        final JsonNode props = node.path("properties");
        if (!props.isObject()) {
            return;
        }
        props.fields().forEachRemaining(entry -> {
            final String propName = entry.getKey();
            final JsonNode literal = entry.getValue();

            final Set<String> requiredStrings = extractRequiredStringFieldNames(literal, root);
            if (!requiredStrings.isEmpty()) {
                out.computeIfAbsent(propName, k -> new HashSet<>()).addAll(requiredStrings);
            }

            // Recurse into the property's resolved schema (the target of any
            // $ref) so nested {@code properties} are visited too. For arrays,
            // descend into the array's element schema.
            final JsonNode resolved = resolveRef(literal, root);
            final JsonNode toRecurse = isArray(resolved)
                    ? resolveRef(resolved.path("items"), root)
                    : resolved;
            walk(toRecurse, root, out);
        });
    }

    /**
     * Given a property's schema declaration (possibly with {@code $ref} and
     * possibly array-typed), return the names of its required properties
     * whose own schema is {@code "type": "string"}. The effective
     * {@code required} list is the union of the literal declaration and any
     * resolved {@code $ref} target — JSON Schema overlay semantics.
     */
    private Set<String> extractRequiredStringFieldNames(final JsonNode literal, final JsonNode root) {
        if (literal == null || literal.isMissingNode()) {
            return Set.of();
        }
        JsonNode literalTarget = literal;
        if (isArray(literalTarget)) {
            literalTarget = literalTarget.path("items");
        }
        final JsonNode resolvedTarget = resolveRef(literalTarget, root);

        // Union of required-arrays from the literal declaration and its $ref target
        final Set<String> requiredNames = new HashSet<>();
        collectRequired(literalTarget, requiredNames);
        if (resolvedTarget != literalTarget) {
            collectRequired(resolvedTarget, requiredNames);
        }
        if (requiredNames.isEmpty()) {
            return Set.of();
        }

        // Resolve each required field's own schema, looking in the literal's
        // properties first and falling back to the resolved target's properties.
        final Set<String> result = new HashSet<>();
        for (final String name : requiredNames) {
            JsonNode propSchema = literalTarget.path("properties").path(name);
            if (propSchema.isMissingNode()) {
                propSchema = resolvedTarget.path("properties").path(name);
            }
            propSchema = resolveRef(propSchema, root);
            if ("string".equals(propSchema.path("type").asText(""))) {
                result.add(name);
            }
        }
        return result;
    }

    private void collectRequired(final JsonNode schemaNode, final Set<String> out) {
        if (schemaNode == null || !schemaNode.isObject()) {
            return;
        }
        final JsonNode required = schemaNode.path("required");
        if (required.isArray()) {
            required.forEach(r -> out.add(r.asText()));
        }
    }

    /** Resolves chained {@code $ref} pointers of the form {@code #/path/to/node}. */
    private JsonNode resolveRef(final JsonNode node, final JsonNode root) {
        if (node == null || node.isMissingNode()) {
            return MissingNode.getInstance();
        }
        JsonNode current = node;
        int hops = 0;
        while (current.has("$ref")) {
            if (hops++ > 16) {
                throw new IllegalStateException("$ref chain too long; possible cycle");
            }
            final String ref = current.get("$ref").asText();
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException("Unsupported $ref (only same-document refs are supported): " + ref);
            }
            JsonNode target = root;
            for (final String part : ref.substring(2).split("/")) {
                target = target.path(part);
            }
            if (target.isMissingNode()) {
                throw new IllegalArgumentException("Unresolved $ref: " + ref);
            }
            current = target;
        }
        return current;
    }

    private boolean isArray(final JsonNode schemaNode) {
        return "array".equals(schemaNode.path("type").asText(""));
    }
}

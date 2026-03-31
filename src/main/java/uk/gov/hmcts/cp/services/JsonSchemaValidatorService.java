package uk.gov.hmcts.cp.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.cp.config.ObjectMapperConfig;
import uk.gov.hmcts.cp.models.transformed.CourtListDocument;

import java.io.IOException;
import java.io.InputStream;

@Service
@Slf4j
public class JsonSchemaValidatorService {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperConfig.getObjectMapper();


    /**
     * Validates a CourtListDocument against the JSON schema.
     *
     * @param document the document to validate
     * @param schemaPath the path to the JSON schema file
     * @throws SchemaValidationException if validation fails
     */
    public void validate(CourtListDocument document, String schemaPath) {
        if (document == null) {
            throw new SchemaValidationException("Document cannot be null");
        }
        if (schemaPath == null || schemaPath.trim().isEmpty()) {
            throw new SchemaValidationException("Schema path cannot be null or empty");
        }

        log.debug("Validating CourtListDocument against JSON schema: {}", schemaPath);

        try {
            Schema schema = loadSchema(schemaPath);
            JSONObject jsonObject = documentToJsonObject(document);

            schema.validate(jsonObject);
            log.debug("JSON schema validation passed");
        } catch (ValidationException e) {
            log.error("JSON schema validation failed");
            String errorMessage = "JSON schema validation failed:\n" + String.join("\n", e.getAllMessages());
            throw new SchemaValidationException(errorMessage, e);
        } catch (IOException e) {
            log.error("Failed to load JSON schema from {}", schemaPath, e);
            throw new SchemaValidationException("JSON schema validation failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Schema format not supported (everit 1.6.0 supports draft-04)", e);
            throw new SchemaValidationException("JSON schema validation failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error during JSON schema validation", e);
            throw new SchemaValidationException("JSON schema validation failed: " + e.getMessage(), e);
        }
    }

    private Schema loadSchema(String schemaPath) throws IOException {
        ClassPathResource resource = new ClassPathResource(schemaPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Schema file not found: " + schemaPath);
        }

        try (InputStream schemaStream = resource.getInputStream()) {
            JSONObject rawSchema = new JSONObject(new JSONTokener(schemaStream));
            JSONObject schemaForEverit = normalizeSchemaForDraft04(rawSchema);
            Schema schema = SchemaLoader.builder()
                    .schemaJson(schemaForEverit)
                    .build()
                    .load()
                    .build();
            log.info("JSON schema loaded successfully from {}", schemaPath);
            return schema;
        }
    }

    /**
     * Normalizes a draft 2020-12 schema (with $defs) so everit 1.6.0 can load it.
     * everit 1.6.0 only supports draft-04, which uses "definitions" and "http://json-schema.org/draft-04/schema#".
     */
    private JSONObject normalizeSchemaForDraft04(JSONObject rawSchema) {
        String json = rawSchema.toString();
        json = json.replace("https://json-schema.org/draft/2020-12/schema", "http://json-schema.org/draft-04/schema#");
        json = json.replace("\"$defs\"", "\"definitions\"");
        json = json.replace("#/$defs/", "#/definitions/");
        return new JSONObject(json);
    }

    private JSONObject documentToJsonObject(CourtListDocument document) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(document);
            return new JSONObject(json);
        } catch (Exception e) {
            throw new SchemaValidationException("Failed to convert document to JSON: " + e.getMessage(), e);
        }
    }
}

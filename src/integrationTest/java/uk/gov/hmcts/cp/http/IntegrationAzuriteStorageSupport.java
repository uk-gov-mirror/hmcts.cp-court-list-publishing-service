package uk.gov.hmcts.cp.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves Azurite / Azure blob settings for integration tests from the same file as Docker Compose
 * ({@code docker/integration-azurite-storage.env}). OS environment variables override file values.
 */
public final class IntegrationAzuriteStorageSupport {

    private static final String ENV_FILE = "integration-azurite-storage.env";

    private static final Map<String, String> FROM_FILE = loadFromDockerEnvFile();

    private IntegrationAzuriteStorageSupport() {
    }

    /**
     * Looks up a variable: {@code System.getenv} wins if set and non-blank; otherwise value from the shared env file.
     */
    public static String get(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return Objects.requireNonNullElse(FROM_FILE.get(key), "").trim();
    }

    public static String requireNonBlank(String key, String description) {
        String v = get(key);
        if (v.isBlank()) {
            throw new IllegalStateException(description + " (" + key + "): set OS env or docker/" + ENV_FILE);
        }
        return v;
    }

    private static Map<String, String> loadFromDockerEnvFile() {
        Path path = resolveEnvFilePath();
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                map.put(k, v);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path + ": " + e.getMessage(), e);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Resolves {@code docker/integration-azurite-storage.env} from the JVM working directory (Gradle uses project root).
     */
    private static Path resolveEnvFilePath() {
        String override = System.getProperty("cp.integration.azureEnvFile");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path direct = cwd.resolve("docker").resolve(ENV_FILE);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        if (cwd.getParent() != null) {
            Path sibling = cwd.getParent().resolve("docker").resolve(ENV_FILE);
            if (Files.isRegularFile(sibling)) {
                return sibling;
            }
        }
        return null;
    }
}

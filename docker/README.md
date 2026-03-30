# Docker assets

## Integration compose and the Azurite “account key”

`integration-azurite-storage.env` (in this directory) holds `AZURE_STORAGE_ACCOUNT_NAME`, `AZURE_STORAGE_CONTAINER_NAME`, and `AZURE_STORAGE_ACCOUNT_KEY`. The integration `app` service loads it via `env_file`, and host-side integration tests read the same file through `IntegrationAzuriteStorageSupport` (working directory should be the project root, as with Gradle).

The key matches the **public, Microsoft-documented** default for the [Azurite](https://github.com/Azure/Azurite) blob emulator account `devstoreaccount1`.

- It is **not** a production secret and is **the same for every project** using the default emulator setup.
- Automated secret scanners (e.g. Gitleaks) may still report it; the env file is marked with `# gitleaks:allow` and this note is for reviewers.
- To use **real** Azure Storage, update `integration-azurite-storage.env` (used by the `app` container). For **host-side** integration tests only, you can instead set the same variable names in the OS environment, which overrides values read from that file.

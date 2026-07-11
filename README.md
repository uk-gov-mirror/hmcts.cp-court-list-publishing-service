# HMCTS Service Spring Boot Template

This repository provides a template for building Spring Boot applications. While the initial use case was for the HMCTS API Marketplace, the template is designed to be reusable across jurisdictions and is intended as a base paved path for wider adoption.

It includes essential configurations, dependencies, and recommended practices to help teams get started quickly.

newline

**Note:** This template is not a framework, nor is it intended to evolve into one. It simply leverages the Spring ecosystem and proven libraries from the wider engineering community.

As HMCTS services are hosted on Azure, the included dependencies reflect this. Our aim is to stay as close to the cloud as possible in order to maximise alignment with the Shared Responsibility Model and achieve optimal security and operability.

## Want to Build Your Own Path?

That’s absolutely fine — but if you do, make sure your approach meets the following baseline requirements:

* Security – All services must meet HMCTS security standards, including vulnerability scanning and least privilege access.
* Observability – Logs, metrics, and traces must be integrated into HMCTS observability stack (e.g. Azure Monitoring).
* Audit – Systems must produce audit trails that meet legal and operational requirements.
* CI/CD Integration – Pipelines must include automated testing, deployments to multiple environments, and use approved tooling (e.g. GitHub Actions or Azure DevOps).
* Compliance & Policy Alignment – Services must align with HMCTS/MoJ policies (e.g. Coding in the Open, mandatory security practices).
* Ownership & Support – Domain teams must clearly own the service, maintain a support model, and define escalation paths.

## Documentation

Further documentation can be found in the [docs](docs) directory.

### Key Documentation
- [Spring Boot v4 Upgrade Guide](docs/SpringUpgradev4.md) - Details on the Spring Boot v4 upgrade, tracing test fixes, and code refactoring improvements
- [Environment Variables Guide](docs/EnvironmentVariables.md) - Complete guide to managing environment variables with `.env` and `.envrc` files
- [JWT Filter Documentation](docs/JWTFilter.md) - JWT authentication filter configuration and usage
- [Logging Documentation](docs/Logging.md) - Logging configuration and best practices
- [Pipeline Documentation](docs/PIPELINE.md) - CI/CD pipeline configuration and deployment processes

### Prerequisites

- ☕️ **Java 21.0.8 or later**: Ensure Java is installed and available on your `PATH`.
- ⚙️ **Gradle**: [Install Gradle](https://gradle.org/install/). The project itself defines which Gradle version to use (gradle/wraper/gradle-wrapper.properties).

You can verify installation with:
```bash
java -version
gradle -v
```

## Installation

### Build
```bash
gradle build
```

`build` will run all tests.

### Tests
- `gradle test` for running unit tests
- `gradle integration` for running integration tests


### Environment Setup for Local Builds

This project uses a two-file approach for environment variable management with `.env` and `.envrc` files. 

**Quick Setup:**
1. Install `direnv`: `brew install direnv`
2. Add to shell: `echo 'eval "$(direnv hook zsh)"' >> ~/.zshrc`
3. Allow direnv: `direnv allow`
4. Create `.env` file with your local configuration

**Server Port:** The application uses port `8082` by default. Override with:
- Environment variable: `export SERVER_PORT=8080`
- Gradle property: `./gradlew integration -Pserver.port=8080`
- System property: `./gradlew integration -Dserver.port=8080`

**Azure Environment Variable:** To use Azure blob storage (upload only; no SAS URLs), set:
- Azure Storage Enable: `export AZURE_STORAGE_ENABLED=true`
- Account Name: `export AZURE_STORAGE_ACCOUNT_NAME=...`
- For local/integration (Azurite): `docker/docker-compose.integration.yml` defaults to Microsoft’s **public** Azurite key (`devstoreaccount1`); see **`docker/README.md`** for why that is safe to commit and why secret scanners may still flag it (we use `# gitleaks:allow` on that line). Override `AZURE_STORAGE_ACCOUNT_KEY` only for a real storage account.
- **Docker must be running** before `gradle composeBuild` / `integration`. The build runs `checkDockerDaemon` first so you get a short Gradle error instead of a vague compose failure. If you see `.../docker/desktop/docker.sock`: start **Docker Desktop**. If you use the Linux **docker.io** engine instead, run `unset DOCKER_HOST` and/or `export DOCKER_HOST=unix:///var/run/docker.sock` (or `docker context use default`).
- **Publishing Hub / APIM**: set `AZURE_LOCAL_DTS_APIMURL` (and other `AZURE_LOCAL_DTS_*` / `AZURE_REMOTE_DTS_*` as needed) in each environment; defaults in `application-azure.yml` no longer embed internal hostnames.

**Database information:** you can use postico to reach DB
- Database : `courtlistpublishing`
- Account Name: `courtlistpublishing`
- Password: `courtlistpublishing`

📖 **For complete setup instructions, troubleshooting, and best practices, see the [Environment Variables Guide](docs/EnvironmentVariables.md).**

## Static code analysis

Install PMD

```bash
brew install pmd
```
```bash
pmd check \
    --dir src/main/java \
    --rulesets \
    .github/pmd-ruleset.xml \
    --format html \
    -r build/reports/pmd/pmd-report.html
```

Run PMD from Gradle

```
gradle pmdTest
```

## Pact Provider Test

Run pact provider test and publish verification report to pact broker locally

Update .env file with below details (replacing placeholders with actual values):
```bash
export PACT_PROVIDER_VERSION="0.1.0-local-YOUR-INITIALS" # or any version you want to use
export PACT_VERIFIER_PUBLISH_RESULTS=true
export PACT_PROVIDER_BRANCH="ANY_BRANCH_NAME_THAT_IS_NOT_A_DEFAULT_ONE"
export PACT_BROKER_TOKEN="YOUR_PACTFLOW_BROKER_TOKEN"
export PACT_BROKER_URL="https://hmcts-dts.pactflow.io"
export PACT_ENV="local" # or value based on the environment you are testing against
```
Run Pact tests:
```bash
./gradlew pactVerificationTest
```

### Contribute to This Repository

Contributions are welcome! Please see the [CONTRIBUTING.md](.github/CONTRIBUTING.md) file for guidelines.

See also: [JWTFilter documentation](docs/JWTFilter.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details

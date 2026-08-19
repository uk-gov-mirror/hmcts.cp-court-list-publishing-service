package uk.gov.hmcts.cp.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import uk.gov.hmcts.cp.domain.CourtListStatusEntity;
import uk.gov.hmcts.cp.openapi.model.CourtListType;
import uk.gov.hmcts.cp.openapi.model.Status;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("testcontainers")
class CourtListStatusRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgresContainer();

    static {
        POSTGRES.start();
    }

    private static PostgreSQLContainer<?> createPostgresContainer() {
        PropertySource<?> source;
        try {
            source = new YamlPropertySourceLoader()
                    .load("application-testcontainers", new ClassPathResource("application-testcontainers.yaml"))
                    .stream().findFirst().orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application-testcontainers.yaml", e);
        }
        return new PostgreSQLContainer<>(get(source, "testcontainers.postgres.image"))
                .withDatabaseName(get(source, "testcontainers.postgres.database-name"))
                .withUsername(get(source, "testcontainers.postgres.username"))
                .withPassword(get(source, "testcontainers.postgres.password"));
    }

    private static String get(PropertySource<?> source, String key) {
        Object value = source != null ? source.getProperty(key) : null;
        return (String) value;
    }

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CourtListStatusRepository repository;

    @AfterEach
    void clearData() {
        repository.deleteAll();
        entityManager.clear();
    }

    @Test
    void save_shouldPersistEntity_whenValidEntityProvided() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        CourtListType courtListType = CourtListType.PUBLIC;
        Instant lastUpdated = Instant.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                courtListType,
                lastUpdated
        );
        entity.setPublishDate(LocalDate.now());

        // When
        CourtListStatusEntity savedEntity = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // Then
        assertThat(savedEntity).isNotNull();
        assertThat(savedEntity.getCourtListId()).isEqualTo(courtListId);
        assertThat(savedEntity.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(savedEntity.getPublishStatus()).isEqualTo(publishStatus);
        assertThat(savedEntity.getCourtListType()).isEqualTo(courtListType);
        assertThat(savedEntity.getLastUpdated()).isEqualTo(lastUpdated);
    }

    @Test
    void save_shouldPersistEntityWithOptionalFields_whenAllFieldsProvided() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        CourtListType courtListType = CourtListType.PUBLIC;
        Instant lastUpdated = Instant.now();
        String fileName = "court-list-2024-01-15.pdf";
        String errorMessage = null;
        LocalDate publishDate = LocalDate.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                courtListType,
                lastUpdated
        );
        entity.setFileUrl(fileName);
        entity.setPublishErrorMessage(errorMessage);
        entity.setPublishDate(publishDate);

        // When
        CourtListStatusEntity savedEntity = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // Then
        CourtListStatusEntity retrievedEntity = repository.findById(courtListId).orElseThrow();
        assertThat(retrievedEntity.getFileUrl()).isEqualTo(fileName);
        assertThat(retrievedEntity.getPublishErrorMessage()).isNull();
        assertThat(retrievedEntity.getPublishDate()).isEqualTo(publishDate);
    }

    @Test
    void findById_shouldReturnEntity_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        CourtListType courtListType = CourtListType.PUBLIC;
        Instant lastUpdated = Instant.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                courtListType,
                lastUpdated
        );
        entity.setPublishDate(LocalDate.now());
        entityManager.persistAndFlush(entity);
        entityManager.clear();

        // When
        Optional<CourtListStatusEntity> foundEntity = repository.findById(courtListId);

        // Then
        assertThat(foundEntity).isPresent();
        assertThat(foundEntity.get().getCourtListId()).isEqualTo(courtListId);
        assertThat(foundEntity.get().getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(foundEntity.get().getPublishStatus()).isEqualTo(publishStatus);
        assertThat(foundEntity.get().getCourtListType()).isEqualTo(courtListType);
    }

    @Test
    void findById_shouldReturnEmpty_whenEntityDoesNotExist() {
        // Given
        UUID nonExistentCourtListId = UUID.randomUUID();

        // When
        Optional<CourtListStatusEntity> foundEntity = repository.findById(nonExistentCourtListId);

        // Then
        assertThat(foundEntity).isEmpty();
    }

    @Test
    void getByCourtListId_shouldReturnEntity_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        CourtListType courtListType = CourtListType.STANDARD;
        Instant lastUpdated = Instant.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                courtListType,
                lastUpdated
        );
        entity.setPublishDate(LocalDate.now());
        entityManager.persistAndFlush(entity);
        entityManager.clear();

        // When
        CourtListStatusEntity foundEntity = repository.getByCourtListId(courtListId);

        // Then
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getCourtListId()).isEqualTo(courtListId);
        assertThat(foundEntity.getCourtCentreId()).isEqualTo(courtCentreId);
        assertThat(foundEntity.getPublishStatus()).isEqualTo(publishStatus);
        assertThat(foundEntity.getCourtListType()).isEqualTo(courtListType);
    }

    @Test
    void findByCourtCentreId_shouldReturnAllEntities_whenMultipleEntitiesExistForSameCourtCentre() {
        // Given
        repository.deleteAll();
        UUID courtCentreId = UUID.randomUUID();
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();
        UUID courtListId3 = UUID.randomUUID();

        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                courtListId1,
                courtCentreId,
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.STANDARD,
                Instant.now()
        );
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                courtListId2,
                courtCentreId,
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.STANDARD,
                Instant.now()
        );
        CourtListStatusEntity entity3 = new CourtListStatusEntity(
                courtListId3,
                UUID.randomUUID(), // Different court centre
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.STANDARD,
                Instant.now()
        );
        entity1.setPublishDate(LocalDate.now());
        entity2.setPublishDate(LocalDate.now());
        entity3.setPublishDate(LocalDate.now());

        entityManager.persist(entity1);
        entityManager.persist(entity2);
        entityManager.persist(entity3);
        entityManager.flush();
        entityManager.clear();

        // When
        List<CourtListStatusEntity> foundEntities = repository.findByCourtCentreId(courtCentreId);

        // Then
        assertThat(foundEntities).hasSize(2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getCourtListId)
                .containsExactlyInAnyOrder(courtListId1, courtListId2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getCourtCentreId)
                .containsOnly(courtCentreId);
    }

    @Test
    void findByPublishStatus_shouldReturnAllEntities_whenMultipleEntitiesExistWithSameStatus() {
        // Given
        repository.deleteAll();
        Status publishStatus = Status.REQUESTED;
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();

        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                courtListId1,
                UUID.randomUUID(),
                publishStatus,
                Status.REQUESTED,
                CourtListType.STANDARD,
                Instant.now()
        );
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                courtListId2,
                UUID.randomUUID(),
                publishStatus,
                Status.REQUESTED,
                CourtListType.FINAL,
                Instant.now()
        );
        entity1.setPublishDate(LocalDate.now());
        entity2.setPublishDate(LocalDate.now());

        entityManager.persist(entity1);
        entityManager.persist(entity2);
        entityManager.flush();
        entityManager.clear();

        // When
        List<CourtListStatusEntity> foundEntities = repository.findByPublishStatus(publishStatus);

        // Then
        assertThat(foundEntities).hasSize(2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getCourtListId)
                .containsExactlyInAnyOrder(courtListId1, courtListId2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getPublishStatus)
                .containsOnly(publishStatus);
    }

    @Test
    void findByCourtCentreIdAndPublishStatus_shouldReturnMatchingEntities_whenBothCriteriaMatch() {
        // Given
        repository.deleteAll();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();

        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                courtListId1,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                CourtListType.FINAL,
                Instant.now()
        );
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                courtListId2,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                CourtListType.FIRM,
                Instant.now()
        );
        entity1.setPublishDate(LocalDate.now());
        entity2.setPublishDate(LocalDate.now());

        entityManager.persist(entity1);
        entityManager.persist(entity2);
        entityManager.flush();
        entityManager.clear();

        // When
        List<CourtListStatusEntity> foundEntities = repository.findByCourtCentreIdAndPublishStatus(
                courtCentreId, publishStatus);

        // Then
        assertThat(foundEntities).hasSize(2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getCourtListId)
                .containsExactlyInAnyOrder(courtListId1, courtListId2);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getCourtCentreId)
                .containsOnly(courtCentreId);
        assertThat(foundEntities).extracting(CourtListStatusEntity::getPublishStatus)
                .containsOnly(publishStatus);
    }

    @Test
    void update_shouldModifyEntity_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status originalStatus = Status.REQUESTED;
        Status newStatus = Status.SUCCESSFUL;
        Instant lastUpdated = Instant.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                originalStatus,
                Status.REQUESTED,
                CourtListType.ONLINE_PUBLIC,
                lastUpdated
        );
        entity.setPublishDate(LocalDate.now());
        entityManager.persistAndFlush(entity);
        entityManager.clear();

        // When
        CourtListStatusEntity existingEntity = repository.findById(courtListId).orElseThrow();
        existingEntity.setPublishStatus(newStatus);
        existingEntity.setFileUrl("updated-file.pdf");
        existingEntity.setPublishErrorMessage(null);
        CourtListStatusEntity updatedEntity = repository.save(existingEntity);
        entityManager.flush();
        entityManager.clear();

        // Then
        CourtListStatusEntity retrievedEntity = repository.findById(courtListId).orElseThrow();
        assertThat(retrievedEntity.getPublishStatus()).isEqualTo(newStatus);
        assertThat(retrievedEntity.getFileUrl()).isEqualTo("updated-file.pdf");
        assertThat(retrievedEntity.getCourtListId()).isEqualTo(courtListId);
    }

    @Test
    void save_shouldPersistPublishCount_whenSet() {
        // Given
        UUID courtListId = UUID.randomUUID();
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.ONLINE_PUBLIC,
                Instant.now()
        );
        entity.setPublishDate(LocalDate.now());
        entity.setPublishCount(5);

        // When
        repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // Then
        CourtListStatusEntity retrieved = repository.findById(courtListId).orElseThrow();
        assertThat(retrieved.getPublishCount()).isEqualTo(5);
    }

    @Test
    void save_shouldDefaultPublishCountToZero_whenNotSet() {
        // Given
        UUID courtListId = UUID.randomUUID();
        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.STANDARD,
                Instant.now()
        );
        entity.setPublishDate(LocalDate.now());

        // When
        repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        // Then
        CourtListStatusEntity retrieved = repository.findById(courtListId).orElseThrow();
        assertThat(retrieved.getPublishCount()).isZero();
    }

    @Test
    void delete_shouldRemoveEntity_whenEntityExists() {
        // Given
        UUID courtListId = UUID.randomUUID();
        UUID courtCentreId = UUID.randomUUID();
        Status publishStatus = Status.REQUESTED;
        CourtListType courtListType = CourtListType.DRAFT;
        Instant lastUpdated = Instant.now();

        CourtListStatusEntity entity = new CourtListStatusEntity(
                courtListId,
                courtCentreId,
                publishStatus,
                Status.REQUESTED,
                courtListType,
                lastUpdated
        );
        entity.setPublishDate(LocalDate.now());
        entityManager.persistAndFlush(entity);
        entityManager.clear();

        // When
        repository.deleteById(courtListId);
        entityManager.flush();
        entityManager.clear();

        // Then
        Optional<CourtListStatusEntity> deletedEntity = repository.findById(courtListId);
        assertThat(deletedEntity).isEmpty();
    }

    @Test
    void findAll_shouldReturnAllEntities_whenMultipleEntitiesExist() {
        // Given
        repository.deleteAll();
        UUID courtListId1 = UUID.randomUUID();
        UUID courtListId2 = UUID.randomUUID();
        UUID courtListId3 = UUID.randomUUID();

        CourtListStatusEntity entity1 = new CourtListStatusEntity(
                courtListId1,
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.FINAL,
                Instant.now()
        );
        CourtListStatusEntity entity2 = new CourtListStatusEntity(
                courtListId2,
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.ONLINE_PUBLIC,
                Instant.now()
        );
        CourtListStatusEntity entity3 = new CourtListStatusEntity(
                courtListId3,
                UUID.randomUUID(),
                Status.REQUESTED,
                Status.REQUESTED,
                CourtListType.ONLINE_PUBLIC,
                Instant.now()
        );
        entity1.setPublishDate(LocalDate.now());
        entity2.setPublishDate(LocalDate.now());
        entity3.setPublishDate(LocalDate.now());

        entityManager.persist(entity1);
        entityManager.persist(entity2);
        entityManager.persist(entity3);
        entityManager.flush();
        entityManager.clear();

        // When
        List<CourtListStatusEntity> allEntities = repository.findAll();

        // Then
        assertThat(allEntities).hasSize(3);
        assertThat(allEntities).extracting(CourtListStatusEntity::getCourtListId)
                .containsExactlyInAnyOrder(courtListId1, courtListId2, courtListId3);
    }

    @Test
    void findByCourtCentreId_shouldReturnEmptyList_whenNoEntitiesExistForCourtCentre() {
        // Given
        UUID nonExistentCourtCentreId = UUID.randomUUID();

        // When
        List<CourtListStatusEntity> foundEntities = repository.findByCourtCentreId(nonExistentCourtCentreId);

        // Then
        assertThat(foundEntities).isEmpty();
    }

    @Test
    void findByCourtCentreIdAndPublishStatus_shouldReturnEmptyList_whenNoMatchingEntitiesExist() {
        // Given
        UUID courtCentreId = UUID.randomUUID();
        // When
        List<CourtListStatusEntity> foundEntities = repository.findByCourtCentreIdAndPublishStatus(
                courtCentreId, Status.FAILED);

        // Then
        assertThat(foundEntities).isEmpty();
    }

    // ── SJP rows: no court centre, no file ───────────────────────────────────

    private CourtListStatusEntity sjpEntity(CourtListType type, LocalDate publishDate) {
        CourtListStatusEntity entity = new CourtListStatusEntity(
                UUID.randomUUID(),
                null,               // SJP is national - no court centre
                Status.SUCCESSFUL,
                null,               // SJP publishes no file
                type,
                Instant.now());
        entity.setPublishDate(publishDate);
        return entity;
    }

    @Test
    void save_shouldPersistSjpRow_withNullCourtCentreIdAndNullFileStatus() {
        CourtListStatusEntity saved = repository.save(
                sjpEntity(CourtListType.SJP_PUBLIC_FULL_ENGLISH, LocalDate.of(2026, 8, 19)));
        entityManager.flush();
        entityManager.clear();

        CourtListStatusEntity reloaded = repository.getByCourtListId(saved.getCourtListId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCourtCentreId()).isNull();
        assertThat(reloaded.getFileStatus()).isNull();
        assertThat(reloaded.getCourtListType()).isEqualTo(CourtListType.SJP_PUBLIC_FULL_ENGLISH);
    }

    @Test
    void findByPublishDateAndCourtListType_shouldLocateSjpRowWithoutACourtCentreId() {
        LocalDate publishDate = LocalDate.of(2026, 8, 19);
        repository.save(sjpEntity(CourtListType.SJP_PRESS_DELTA_WELSH, publishDate));
        entityManager.flush();
        entityManager.clear();

        Optional<CourtListStatusEntity> found = repository.findByPublishDateAndCourtListType(
                publishDate, CourtListType.SJP_PRESS_DELTA_WELSH);

        assertThat(found).isPresent();
        assertThat(found.get().getCourtCentreId()).isNull();
    }

    @Test
    void findByPublishDateAndCourtListType_shouldNotConfuseLanguagesOrRequestTypes() {
        LocalDate publishDate = LocalDate.of(2026, 8, 19);
        repository.save(sjpEntity(CourtListType.SJP_PUBLIC_FULL_ENGLISH, publishDate));
        repository.save(sjpEntity(CourtListType.SJP_PUBLIC_FULL_WELSH, publishDate));
        repository.save(sjpEntity(CourtListType.SJP_PUBLIC_DELTA_ENGLISH, publishDate));
        repository.save(sjpEntity(CourtListType.SJP_PUBLIC_DELTA_WELSH, publishDate));
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByPublishDateAndCourtListType(
                publishDate, CourtListType.SJP_PUBLIC_DELTA_WELSH)).isPresent();
        assertThat(repository.findAll()).hasSize(4);
    }
}

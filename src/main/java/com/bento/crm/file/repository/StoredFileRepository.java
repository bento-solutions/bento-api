package com.bento.crm.file.repository;

import com.bento.crm.file.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    @Query("SELECT f FROM StoredFile f WHERE f.organizationId = :orgId AND f.id = :id")
    Optional<StoredFile> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT f FROM StoredFile f WHERE f.organizationId = :orgId AND f.ownerEntityType = :ownerEntityType AND f.ownerEntityId = :ownerEntityId ORDER BY f.uploadedAt DESC")
    List<StoredFile> findByOrganizationIdAndOwnerEntityTypeAndOwnerEntityId(@Param("orgId") UUID orgId, @Param("ownerEntityType") String ownerEntityType, @Param("ownerEntityId") UUID ownerEntityId);

    @Query(value = "SELECT * FROM stored_file WHERE id = :id AND owner_entity_type = 'ORGANIZATION'", nativeQuery = true)
    Optional<StoredFile> findPublicById(@Param("id") UUID id);
}

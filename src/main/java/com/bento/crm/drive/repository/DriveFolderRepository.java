package com.bento.crm.drive.repository;

import com.bento.crm.drive.model.DriveFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriveFolderRepository extends JpaRepository<DriveFolder, UUID> {

    @Query("SELECT f FROM DriveFolder f WHERE f.organizationId = :orgId AND f.id = :id AND f.deletedAt IS NULL")
    Optional<DriveFolder> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT f FROM DriveFolder f WHERE f.organizationId = :orgId AND f.parentId = :parentId AND f.deletedAt IS NULL ORDER BY lower(f.name)")
    List<DriveFolder> children(@Param("orgId") UUID orgId, @Param("parentId") UUID parentId);

    @Query("SELECT f FROM DriveFolder f WHERE f.organizationId = :orgId AND f.parentId IS NULL AND f.deletedAt IS NULL ORDER BY lower(f.name)")
    List<DriveFolder> roots(@Param("orgId") UUID orgId);

    @Query("SELECT f FROM DriveFolder f WHERE f.organizationId = :orgId AND f.systemKey = :key AND f.deletedAt IS NULL")
    Optional<DriveFolder> findBySystemKey(@Param("orgId") UUID orgId, @Param("key") String key);

    @Query("SELECT f FROM DriveFolder f WHERE f.organizationId = :orgId AND f.deletedAt IS NULL")
    List<DriveFolder> all(@Param("orgId") UUID orgId);
}

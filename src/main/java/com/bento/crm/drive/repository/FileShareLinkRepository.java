package com.bento.crm.drive.repository;

import com.bento.crm.drive.model.FileShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileShareLinkRepository extends JpaRepository<FileShareLink, UUID> {

    Optional<FileShareLink> findByToken(String token);

    @Query("SELECT l FROM FileShareLink l WHERE l.organizationId = :orgId AND l.fileId = :fileId AND l.deletedAt IS NULL ORDER BY l.createdAt DESC")
    List<FileShareLink> forFile(@Param("orgId") UUID orgId, @Param("fileId") UUID fileId);

    @Query("SELECT l FROM FileShareLink l WHERE l.organizationId = :orgId AND l.id = :id AND l.deletedAt IS NULL")
    Optional<FileShareLink> find(@Param("orgId") UUID orgId, @Param("id") UUID id);
}

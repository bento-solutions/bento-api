package com.bento.crm.category.repository;

import com.bento.crm.category.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("SELECT c FROM Category c WHERE c.organizationId = :orgId AND c.deletedAt IS NULL ORDER BY lower(c.name) ASC")
    List<Category> findAllActiveByOrganizationId(@Param("orgId") UUID orgId);

    @Query("SELECT c FROM Category c WHERE c.organizationId = :orgId AND c.id = :id AND c.deletedAt IS NULL")
    Optional<Category> findByOrganizationIdAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT c FROM Category c WHERE c.organizationId = :orgId AND lower(c.name) = lower(:name) AND c.deletedAt IS NULL")
    Optional<Category> findByOrganizationIdAndNameIgnoreCase(@Param("orgId") UUID orgId, @Param("name") String name);
}

package com.bento.crm.tax.repository;

import com.bento.crm.tax.model.TaxProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxProfileRepository extends JpaRepository<TaxProfile, UUID> {

    @Query("SELECT x FROM TaxProfile x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<TaxProfile> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM TaxProfile x WHERE x.organizationId = :orgId AND x.deletedAt IS NULL ORDER BY x.createdAt")
    List<TaxProfile> all(@Param("orgId") UUID orgId);

    @Query("SELECT x FROM TaxProfile x WHERE x.active = true AND x.deletedAt IS NULL")
    List<TaxProfile> allActive();
}

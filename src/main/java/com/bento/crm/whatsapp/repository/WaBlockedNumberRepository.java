package com.bento.crm.whatsapp.repository;

import com.bento.crm.whatsapp.model.WaBlockedNumber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WaBlockedNumberRepository extends JpaRepository<WaBlockedNumber, UUID> {

    @Query("SELECT COUNT(b) > 0 FROM WaBlockedNumber b WHERE b.organizationId = :orgId AND b.phoneE164 = :phone")
    boolean isBlocked(@Param("orgId") UUID orgId, @Param("phone") String phoneE164);

    @Query("SELECT b FROM WaBlockedNumber b WHERE b.organizationId = :orgId AND b.phoneE164 = :phone")
    Optional<WaBlockedNumber> findByOrgAndPhone(@Param("orgId") UUID orgId, @Param("phone") String phoneE164);

    @Query("SELECT b FROM WaBlockedNumber b WHERE b.organizationId = :orgId ORDER BY b.createdAt DESC")
    List<WaBlockedNumber> findAllForOrg(@Param("orgId") UUID orgId);
}

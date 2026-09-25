package com.bento.crm.apitoken.repository;

import com.bento.crm.apitoken.model.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    @Query("SELECT t FROM ApiToken t WHERE t.tokenHash = :hash")
    Optional<ApiToken> findByHash(@Param("hash") String hash);

    @Query("SELECT t FROM ApiToken t WHERE t.organizationId = :orgId AND t.id = :id")
    Optional<ApiToken> findByOrgAndId(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT t FROM ApiToken t WHERE t.organizationId = :orgId AND t.userId = :userId ORDER BY t.createdAt DESC")
    List<ApiToken> findForUser(@Param("orgId") UUID orgId, @Param("userId") UUID userId);

    @Query("SELECT t FROM ApiToken t WHERE t.organizationId = :orgId ORDER BY t.createdAt DESC")
    List<ApiToken> findForOrg(@Param("orgId") UUID orgId);

    @Modifying
    @Query("UPDATE ApiToken t SET t.lastUsedAt = :at WHERE t.id = :id")
    int touch(@Param("id") UUID id, @Param("at") Instant at);
}

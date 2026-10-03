package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash AND rt.expiresAt > :now AND rt.revokedAt IS NULL")
    Optional<RefreshToken> findValidToken(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.id = :id")
    void revokeToken(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.userId = :userId AND rt.revokedAt IS NULL")
    void revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Looks a token up regardless of whether it is still valid.
     *
     * <p>Needed for reuse detection: a token that is expired or already revoked must be
     * distinguishable from one that never existed, because replaying a revoked token is the
     * signature of a stolen refresh token and has to invalidate the whole family.
     */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    /** True once the token has been rotated: its successor records it as the token it replaced. */
    boolean existsByReplacedByTokenId(UUID replacedByTokenId);

    /** Housekeeping for {@link com.bento.crm.identity.service.RefreshTokenPurgeScheduler}. */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}

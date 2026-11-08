package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.AccountToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    Optional<AccountToken> findByTokenHashAndPurpose(String tokenHash, AccountToken.Purpose purpose);

    /** Whether a link of this kind went out recently, so a resend can be skipped. */
    boolean existsByAccountIdAndPurposeAndCreatedAtAfter(UUID accountId, AccountToken.Purpose purpose, Instant after);

    /** Retires every outstanding link of one kind, so only the newest one works. */
    @Modifying
    @Query("UPDATE AccountToken t SET t.usedAt = :now WHERE t.accountId = :accountId AND t.purpose = :purpose AND t.usedAt IS NULL")
    void retireOutstanding(@Param("accountId") UUID accountId, @Param("purpose") AccountToken.Purpose purpose, @Param("now") Instant now);
}

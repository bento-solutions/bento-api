package com.bento.crm.whatsapp.repository;

import com.bento.crm.whatsapp.model.WaAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WaAccountRepository extends JpaRepository<WaAccount, UUID> {

    Optional<WaAccount> findByOrganizationId(UUID organizationId);

    /**
     * Resolves an inbound Meta webhook to a tenant. This is the only tenant key the
     * callback carries, which is why it is unique across organizations.
     */
    Optional<WaAccount> findByPhoneNumberId(String phoneNumberId);

    /**
     * Locks the account row for the outbox claim. Holding it serializes the claimers of one
     * account, which is what makes "one send in flight per paced account" and the pacing counts
     * race-free across scheduler ticks, kicks and application instances.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM WaAccount a WHERE a.organizationId = :orgId")
    Optional<WaAccount> findByOrganizationIdForUpdate(@Param("orgId") UUID orgId);
}

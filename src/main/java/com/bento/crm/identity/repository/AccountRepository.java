package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** {@code email} must already be normalized ({@link Account#normalizeEmail}). */
    Optional<Account> findByEmail(String email);
}

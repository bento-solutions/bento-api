package com.bento.crm.inbound.repository;

import com.bento.crm.inbound.model.InboundMailbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboundMailboxRepository extends JpaRepository<InboundMailbox, UUID> {

    @Query("SELECT m FROM InboundMailbox m WHERE lower(m.token) = lower(:token) AND m.deletedAt IS NULL")
    Optional<InboundMailbox> findByToken(@Param("token") String token);

    @Query("SELECT m FROM InboundMailbox m WHERE m.organizationId = :orgId AND m.deletedAt IS NULL ORDER BY m.kind")
    List<InboundMailbox> forOrganization(@Param("orgId") UUID orgId);

    @Query("SELECT m FROM InboundMailbox m WHERE m.organizationId = :orgId AND m.id = :id AND m.deletedAt IS NULL")
    Optional<InboundMailbox> find(@Param("orgId") UUID orgId, @Param("id") UUID id);
}

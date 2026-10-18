package com.bento.crm.portal.repository;

import com.bento.crm.portal.model.PortalUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalUserRepository extends JpaRepository<PortalUser, UUID> {

    @Query("SELECT u FROM PortalUser u WHERE u.organizationId = :orgId AND u.id = :id AND u.deletedAt IS NULL")
    Optional<PortalUser> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT u FROM PortalUser u WHERE u.organizationId = :orgId AND u.partnerId = :partnerId AND u.deletedAt IS NULL ORDER BY u.createdAt")
    List<PortalUser> forPartner(@Param("orgId") UUID orgId, @Param("partnerId") UUID partnerId);

    @Query("SELECT u FROM PortalUser u WHERE u.organizationId = :orgId AND lower(u.email) = lower(:email) AND u.deletedAt IS NULL")
    Optional<PortalUser> byEmail(@Param("orgId") UUID orgId, @Param("email") String email);

    /** Across organizations: a person may be the client of several firms using Bento. */
    @Query("SELECT u FROM PortalUser u WHERE lower(u.email) = lower(:email) AND u.active = true AND u.deletedAt IS NULL")
    List<PortalUser> activeByEmail(@Param("email") String email);

    @Query("SELECT u FROM PortalUser u WHERE u.inviteTokenHash = :hash AND u.deletedAt IS NULL")
    Optional<PortalUser> byInviteHash(@Param("hash") String hash);
}

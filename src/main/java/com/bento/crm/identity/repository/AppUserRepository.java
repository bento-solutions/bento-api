package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.AppUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @Query("SELECT u FROM AppUser u WHERE u.organizationId = :organizationId AND lower(u.email) = lower(:email)")
    Optional<AppUser> findByOrganizationIdAndEmail(@Param("organizationId") UUID organizationId, @Param("email") String email);

    /**
     * Cross-tenant lookup by email, used only by the login and signup paths.
     *
     * <p>Email is unique per organization, not globally, so this can legitimately return more
     * than one row — the same person may hold accounts in several tenants. Login disambiguates
     * by password; signup uses it to reject an address that is already in use.
     *
     * <p>Deliberately not org-scoped, which is why it is spelled out here rather than being one
     * more caller of a generic finder: it is the only query in the application that is allowed to
     * cross tenants, and it must stay easy to spot in review.
     */
    @Query(value = "SELECT * FROM app_user u WHERE lower(u.email) = lower(:email) ORDER BY u.created_at", nativeQuery = true)
    List<AppUser> findAllByEmailAcrossOrganizations(@Param("email") String email);

    @Query("SELECT u FROM AppUser u WHERE u.organizationId = :organizationId AND u.isActive = true")
    List<AppUser> findActiveByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.organizationId = :organizationId AND u.role = 'ADMIN' AND u.isActive = true")
    long countActiveAdminsByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT u FROM AppUser u WHERE u.organizationId = :organizationId")
    List<AppUser> findByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT u FROM AppUser u WHERE u.organizationId = :organizationId")
    Page<AppUser> findByOrganizationId(@Param("organizationId") UUID organizationId, Pageable pageable);

    @Query("SELECT u FROM AppUser u WHERE u.organizationId = :organizationId AND u.id = :id")
    Optional<AppUser> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);
}

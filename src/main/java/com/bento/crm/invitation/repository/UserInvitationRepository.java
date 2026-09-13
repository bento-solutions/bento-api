package com.bento.crm.invitation.repository;

import com.bento.crm.invitation.model.InvitationStatus;
import com.bento.crm.invitation.model.UserInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserInvitationRepository extends JpaRepository<UserInvitation, UUID> {

    /**
     * Deliberately not scoped to an organization: the acceptance endpoint is unauthenticated, so
     * the token is the only thing identifying the tenant. The token is 256 bits of entropy and
     * only its hash is stored, which is what makes an org-wide lookup safe here.
     */
    @Query("SELECT i FROM UserInvitation i WHERE i.tokenHash = :tokenHash")
    Optional<UserInvitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("SELECT i FROM UserInvitation i WHERE i.organizationId = :organizationId ORDER BY i.createdAt DESC")
    List<UserInvitation> findByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("SELECT i FROM UserInvitation i WHERE i.organizationId = :organizationId AND i.id = :id")
    Optional<UserInvitation> findByOrganizationIdAndId(@Param("organizationId") UUID organizationId, @Param("id") UUID id);

    @Query("SELECT i FROM UserInvitation i WHERE i.organizationId = :organizationId "
            + "AND LOWER(i.email) = LOWER(:email) AND i.status = :status")
    Optional<UserInvitation> findByOrganizationIdAndEmailAndStatus(@Param("organizationId") UUID organizationId,
                                                                   @Param("email") String email,
                                                                   @Param("status") InvitationStatus status);

    @Query(value = "SELECT * FROM user_invitation WHERE lower(email) = lower(:email) AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP ORDER BY created_at DESC", nativeQuery = true)
    List<UserInvitation> findPendingByEmailAcrossOrganizations(@Param("email") String email);

    @Query(value = "SELECT * FROM user_invitation WHERE id = :id", nativeQuery = true)
    Optional<UserInvitation> findByIdAcrossOrganizations(@Param("id") UUID id);
}

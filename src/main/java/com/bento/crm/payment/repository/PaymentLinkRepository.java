package com.bento.crm.payment.repository;

import com.bento.crm.payment.model.PaymentLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {

    /** Public lookup: the token is unique across organizations. */
    Optional<PaymentLink> findByTokenAndDeletedAtIsNull(String token);

    Optional<PaymentLink> findByProviderSessionId(String providerSessionId);

    @Query("""
            SELECT l FROM PaymentLink l
            WHERE l.organizationId = :orgId AND l.invoiceId = :invoiceId AND l.deletedAt IS NULL
            ORDER BY l.createdAt DESC
            """)
    List<PaymentLink> findForInvoice(@Param("orgId") UUID orgId, @Param("invoiceId") UUID invoiceId);
}

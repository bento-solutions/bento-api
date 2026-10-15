package com.bento.crm.payment.repository;

import com.bento.crm.payment.model.PaymentSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, UUID> {

    Optional<PaymentSettings> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);
}

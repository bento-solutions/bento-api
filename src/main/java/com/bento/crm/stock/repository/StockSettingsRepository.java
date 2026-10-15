package com.bento.crm.stock.repository;

import com.bento.crm.stock.model.StockSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StockSettingsRepository extends JpaRepository<StockSettings, UUID> {

    Optional<StockSettings> findByOrganizationIdAndDeletedAtIsNull(UUID organizationId);
}

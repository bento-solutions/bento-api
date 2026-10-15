package com.bento.crm.invoice.repository;

import com.bento.crm.invoice.model.BillAlertDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface BillAlertDigestRepository extends JpaRepository<BillAlertDigest, UUID> {

    @Query("SELECT COUNT(d) > 0 FROM BillAlertDigest d WHERE d.organizationId = :orgId AND d.alertDate = :day")
    boolean existsForDay(@Param("orgId") UUID orgId, @Param("day") LocalDate day);
}

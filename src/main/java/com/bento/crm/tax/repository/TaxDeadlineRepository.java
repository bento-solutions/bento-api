package com.bento.crm.tax.repository;

import com.bento.crm.tax.model.TaxDeadline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxDeadlineRepository extends JpaRepository<TaxDeadline, UUID> {

    @Query("SELECT x FROM TaxDeadline x WHERE x.organizationId = :orgId AND x.id = :id AND x.deletedAt IS NULL")
    Optional<TaxDeadline> find(@Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query("SELECT x FROM TaxDeadline x WHERE x.status = com.bento.crm.tax.model.TaxDeadline.Status.TODO"
            + " AND x.remindedAt IS NULL AND x.dueDate <= :until AND x.deletedAt IS NULL")
    List<TaxDeadline> dueForReminder(@Param("until") LocalDate until);
}

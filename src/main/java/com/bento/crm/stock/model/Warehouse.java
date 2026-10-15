package com.bento.crm.stock.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "warehouse")
@Getter
@Setter
public class Warehouse extends BaseTenantEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 30)
    private String code;

    @Column(columnDefinition = "text")
    private String address;

    /** Where automatic receipts and issues happen. Exactly one per organization. */
    @Column(nullable = false)
    private Boolean isDefault = false;

    @Column(nullable = false)
    private Boolean isActive = true;
}

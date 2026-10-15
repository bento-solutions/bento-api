package com.bento.crm.drive.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "drive_folder")
@Getter
@Setter
public class DriveFolder extends BaseTenantEntity {

    @Column(name = "parent_id", columnDefinition = "uuid")
    private UUID parentId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "partner_id", columnDefinition = "uuid")
    private UUID partnerId;

    /** Set on folders the system files documents into; those cannot be renamed or deleted. */
    @Column(name = "system_key", length = 80)
    private String systemKey;

    /** Authority needed, on top of FILES_READ, to see this folder's files. */
    @Column(name = "required_authority", length = 40)
    private String requiredAuthority;
}

package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.GroupMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GroupMessageRepository extends JpaRepository<GroupMessage, UUID> {

    @Query("SELECT gm FROM GroupMessage gm WHERE gm.organizationId = :organizationId AND gm.groupId = :groupId ORDER BY gm.createdAt DESC")
    Page<GroupMessage> findByGroupId(@Param("organizationId") UUID organizationId, @Param("groupId") UUID groupId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM GroupMessage gm WHERE gm.organizationId = :organizationId AND gm.groupId = :groupId")
    void deleteByGroupId(@Param("organizationId") UUID organizationId, @Param("groupId") UUID groupId);
}

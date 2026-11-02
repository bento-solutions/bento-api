package com.bento.crm.identity.repository;

import com.bento.crm.identity.model.GroupMeeting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface GroupMeetingRepository extends JpaRepository<GroupMeeting, UUID> {

    @Query("SELECT gm FROM GroupMeeting gm WHERE gm.organizationId = :organizationId AND gm.groupId = :groupId ORDER BY gm.scheduledAt DESC")
    Page<GroupMeeting> findByGroupId(@Param("organizationId") UUID organizationId, @Param("groupId") UUID groupId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM GroupMeeting gm WHERE gm.organizationId = :organizationId AND gm.groupId = :groupId")
    void deleteByGroupId(@Param("organizationId") UUID organizationId, @Param("groupId") UUID groupId);
}

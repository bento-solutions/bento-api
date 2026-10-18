package com.bento.crm.mission.dto;

import com.bento.crm.mission.model.Mission;
import com.bento.crm.mission.model.MissionDeliverable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MissionDtos {

    private MissionDtos() {}

    public record Summary(UUID id, UUID partnerId, String partnerName, String name, Mission.Status status,
                          LocalDate startDate, LocalDate dueDate, BigDecimal feeAmount, UUID assignedUserId,
                          String assignedUserName, int deliverables, int delivered, BigDecimal unbilledDisbursements,
                          boolean overdue) {}

    public record Detail(Summary mission, String description, List<Deliverable> deliverables,
                         List<DisbursementRow> disbursements) {}

    public record MissionRequest(UUID partnerId, String name, String description, Mission.Status status,
                                 LocalDate startDate, LocalDate dueDate, BigDecimal feeAmount, UUID assignedUserId) {}

    public record Deliverable(UUID id, UUID missionId, String title, LocalDate dueDate, MissionDeliverable.Status status,
                              UUID fileId, String fileName, Instant deliveredAt, boolean visibleToClient) {}

    public record DeliverableRequest(String title, LocalDate dueDate, MissionDeliverable.Status status, UUID fileId,
                                     Boolean removeFile, Boolean visibleToClient) {}

    public record DisbursementRow(UUID id, UUID partnerId, String partnerName, UUID missionId, String missionName,
                                  LocalDate spentOn, String description, BigDecimal amount, BigDecimal vatRate,
                                  boolean rebillable, UUID invoiceId, String invoiceNumber, UUID receiptFileId) {}

    public record DisbursementRequest(UUID partnerId, UUID missionId, LocalDate spentOn, String description,
                                      BigDecimal amount, BigDecimal vatRate, Boolean rebillable, UUID receiptFileId) {}

    public record BillRequest(UUID partnerId, List<UUID> disbursementIds) {}

    public record BillResult(UUID invoiceId, String invoiceNumber, int lines, BigDecimal total) {}
}

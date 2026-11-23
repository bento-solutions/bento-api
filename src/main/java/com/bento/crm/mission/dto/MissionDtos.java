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
                          boolean overdue, UUID dealId) {}

    public record Detail(Summary mission, String description, List<Deliverable> deliverables,
                         List<DisbursementRow> disbursements) {}

    public record MissionRequest(UUID partnerId, String name, String description, Mission.Status status,
                                 LocalDate startDate, LocalDate dueDate, BigDecimal feeAmount, UUID assignedUserId,
                                 UUID dealId) {}

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

    // Mission summary (phase 3 part C)

    public record CostRow(String accountCode, String accountName, BigDecimal amount) {}

    /** type: PROPOSAL, DEAL, DELIVERY_NOTE, INVOICE, ADVANCE, BILL or EXPENSE. */
    public record DocumentRow(String type, UUID id, String number, LocalDate date, BigDecimal amount, String status) {}

    /** Costs of the mission on one cost center; costCenterId, code and name are null for lines without one. */
    public record CostCenterCost(UUID costCenterId, String code, String name, BigDecimal amount) {}

    /**
     * costCenterId is the mission's legacy PRJ cost center, if it has one. costs and costsByCostCenter
     * come from the class 6 lines with the mission as dimension, plus the legacy lines on that cost center.
     */
    public record MissionSummary(UUID missionId, UUID dealId, UUID proposalId, UUID costCenterId, BigDecimal quoted,
                                 BigDecimal invoiced, BigDecimal advances, List<CostRow> costs, BigDecimal costsTotal,
                                 BigDecimal margin, List<DocumentRow> documents, List<CostCenterCost> costsByCostCenter) {}

    public record BillResult(UUID invoiceId, String invoiceNumber, int lines, BigDecimal total) {}
}

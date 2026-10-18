package com.bento.crm.mission.controller;

import com.bento.crm.mission.dto.MissionDtos;
import com.bento.crm.mission.model.Mission;
import com.bento.crm.mission.service.MissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Client missions, deliverables and rebillable disbursements. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Missions")
public class MissionController {

    private static final String READ = "hasAuthority('PARTNERS_READ')";
    private static final String WRITE = "hasAuthority('PARTNERS_WRITE')";

    private final MissionService service;

    @GetMapping("/missions")
    @PreAuthorize(READ)
    public List<MissionDtos.Summary> list(@RequestParam(required = false) UUID partnerId,
                                          @RequestParam(required = false) Mission.Status status) {
        return service.list(partnerId, status);
    }

    @GetMapping("/missions/{id}")
    @PreAuthorize(READ)
    public MissionDtos.Detail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/missions")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public MissionDtos.Detail create(@RequestBody MissionDtos.MissionRequest r) {
        return service.create(r);
    }

    @PutMapping("/missions/{id}")
    @PreAuthorize(WRITE)
    public MissionDtos.Detail update(@PathVariable UUID id, @RequestBody MissionDtos.MissionRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/missions/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/missions/{id}/deliverables")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public MissionDtos.Deliverable addDeliverable(@PathVariable UUID id, @RequestBody MissionDtos.DeliverableRequest r) {
        return service.addDeliverable(id, r);
    }

    @PutMapping("/mission-deliverables/{id}")
    @PreAuthorize(WRITE)
    public MissionDtos.Deliverable updateDeliverable(@PathVariable UUID id, @RequestBody MissionDtos.DeliverableRequest r) {
        return service.updateDeliverable(id, r);
    }

    @DeleteMapping("/mission-deliverables/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDeliverable(@PathVariable UUID id) {
        service.deleteDeliverable(id);
    }

    @GetMapping("/disbursements")
    @PreAuthorize(READ)
    public List<MissionDtos.DisbursementRow> disbursements(@RequestParam(required = false) UUID partnerId,
                                                           @RequestParam(required = false) UUID missionId,
                                                           @RequestParam(defaultValue = "false") boolean unbilled) {
        return service.disbursements(partnerId, missionId, unbilled);
    }

    @PostMapping("/disbursements")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public MissionDtos.DisbursementRow addDisbursement(@RequestBody MissionDtos.DisbursementRequest r) {
        return service.addDisbursement(r);
    }

    @PutMapping("/disbursements/{id}")
    @PreAuthorize(WRITE)
    public MissionDtos.DisbursementRow updateDisbursement(@PathVariable UUID id, @RequestBody MissionDtos.DisbursementRequest r) {
        return service.updateDisbursement(id, r);
    }

    @DeleteMapping("/disbursements/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDisbursement(@PathVariable UUID id) {
        service.deleteDisbursement(id);
    }

    @PostMapping("/disbursements/bill")
    @PreAuthorize("hasAuthority('PARTNERS_WRITE') and hasAuthority('INVOICES_CREATE')")
    @ResponseStatus(HttpStatus.CREATED)
    public MissionDtos.BillResult bill(@RequestBody MissionDtos.BillRequest r) {
        return service.bill(r);
    }
}

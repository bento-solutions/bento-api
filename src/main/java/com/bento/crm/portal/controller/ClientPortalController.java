package com.bento.crm.portal.controller;

import com.bento.crm.portal.dto.PortalDtos;
import com.bento.crm.portal.service.PortalAccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Staff manage who at a client company can sign in to the portal. */
@RestController
@RequestMapping("/client-portal")
@RequiredArgsConstructor
@Tag(name = "Client portal accounts")
public class ClientPortalController {

    private static final String READ = "hasAuthority('PARTNERS_READ')";
    private static final String WRITE = "hasAuthority('PARTNERS_WRITE')";

    private final PortalAccountService accounts;

    @GetMapping("/accounts")
    @PreAuthorize(READ)
    public List<PortalDtos.Account> accounts(@RequestParam UUID partnerId) {
        return accounts.accounts(partnerId);
    }

    /** Invites a new person, or sends a fresh link to an existing one. */
    @PostMapping("/accounts")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public PortalDtos.Invited invite(@RequestBody PortalDtos.InviteRequest r) {
        return accounts.invite(r);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize(WRITE)
    public PortalDtos.Account update(@PathVariable UUID id, @RequestBody PortalDtos.AccountUpdate r) {
        return accounts.update(id, r);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        accounts.delete(id);
    }
}

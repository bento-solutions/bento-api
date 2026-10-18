package com.bento.crm.portal.controller;

import com.bento.crm.portal.dto.PortalDtos;
import com.bento.crm.portal.service.PortalAccountService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Client portal sign-in. Public: the invitation or reset token, or the password, is the credential. */
@RestController
@RequestMapping("/portal/auth")
@RequiredArgsConstructor
@Tag(name = "Client portal")
public class PortalAuthController {

    private final PortalAccountService accounts;

    /** A session, or, when the email is a client of several firms, the firms to choose from. */
    @PostMapping("/login")
    public Object login(@RequestBody PortalDtos.LoginRequest r) {
        return accounts.login(r);
    }

    @PostMapping("/accept")
    public PortalDtos.Session accept(@RequestBody PortalDtos.AcceptRequest r) {
        return accounts.accept(r);
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgot(@RequestBody PortalDtos.ForgotRequest r) {
        accounts.forgot(r);
    }

    @PostMapping("/reset")
    public PortalDtos.Session reset(@RequestBody PortalDtos.ResetRequest r) {
        return accounts.reset(r);
    }
}

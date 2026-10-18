package com.bento.crm.push;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The mobile app registers its push token after sign-in and removes it on sign-out. */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Mobile push")
public class PushController {

    private final PushService push;

    @GetMapping("/push-status")
    public Map<String, Boolean> status() {
        return Map.of("enabled", push.enabled());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@RequestBody PushService.Registration r) {
        push.register(r);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@RequestBody Map<String, String> body) {
        push.unregister(body.get("token"));
    }
}

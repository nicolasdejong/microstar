package net.microstar.dispatcher.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping(produces = "application/json")
public class InfoController {

    @RequestMapping("/version")
    public ResponseEntity<ServiceId> version() {
        return ResponseEntity.ok(ServiceId.get());
    }

    @GetMapping(value = "/ok", produces = "text/plain")
    public ResponseEntity<String> ok() {
        return ResponseEntity.ok("Ok");
    }
}

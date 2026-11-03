package com.docchat.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Availability of the backend and its dependencies")
class SystemStatusController {

    private final SystemStatusService systemStatusService;

    SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("/status")
    @Operation(
            summary = "Report the status of the backend's dependencies",
            description = "Always answers 200 while the backend is running, with the real state of"
                    + " each dependency in the body. Probes should use /actuator/health/readiness,"
                    + " which answers 503 when a dependency is down.")
    SystemStatus status() {
        return systemStatusService.currentStatus();
    }
}

package com.interviewer.rpc;

import com.interviewer.rpc.dto.ServerInfo;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "meta")
public class MetaController {

    private final EventHub hub;

    public MetaController(EventHub hub) {
        this.hub = hub;
    }

    @GetMapping("/info")
    public ServerInfo info() {
        return new ServerInfo("interviewer-rpc", "1", EventHub.EVENT_NAMES, hub.clientCount());
    }
}

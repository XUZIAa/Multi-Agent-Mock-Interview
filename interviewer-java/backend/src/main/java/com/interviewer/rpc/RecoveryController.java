package com.interviewer.rpc;

import com.interviewer.orchestration.RecoveryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "recovery")
public class RecoveryController {

    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/recovery/scan")
    public List<RecoveryService.InterruptedSession> scan() {
        return recovery.scan();
    }
}

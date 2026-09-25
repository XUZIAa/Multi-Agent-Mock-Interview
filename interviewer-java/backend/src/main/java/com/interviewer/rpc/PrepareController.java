package com.interviewer.rpc;

import com.interviewer.data.dto.StoredGap;
import com.interviewer.data.dto.StoredJob;
import com.interviewer.data.dto.StoredResume;
import com.interviewer.domain.interview.InterviewState;
import com.interviewer.orchestration.PrepareService;
import com.interviewer.rpc.dto.BuildSessionBody;
import com.interviewer.rpc.dto.DiagnoseBody;
import com.interviewer.rpc.dto.IngestJobTextBody;
import com.interviewer.rpc.dto.IngestPathBody;
import com.interviewer.rpc.dto.SynthesizeJobBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "prepare")
public class PrepareController {

    private final PrepareService prepare;
    private final EventHub hub;

    public PrepareController(PrepareService prepare, EventHub hub) {
        this.prepare = prepare;
        this.hub = hub;
    }

    /** 把同步进度回调转成 task_progress 事件。回调在业务线程上同步调用，不能阻塞。 */
    private PrepareService.Progress progress(String taskId) {
        return (stage, percent) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", taskId);
            data.put("stage", stage);
            data.put("percent", percent);
            hub.publish("task_progress", data);
        };
    }

    @PostMapping("/prepare/session")
    public InterviewState buildSession(@Valid @RequestBody BuildSessionBody body) {
        return prepare.buildSession(body.persona(), body.resumeId(), body.jobId(), body.tier(),
                body.level(), body.minutes(), body.codingEnabled(), progress(body.taskId()));
    }

    @PostMapping("/prepare/resume")
    public StoredResume ingestResume(@Valid @RequestBody IngestPathBody body) {
        return prepare.ingestResume(Path.of(body.path()), progress(body.taskId()));
    }

    @PostMapping("/prepare/job-file")
    public StoredJob ingestJobFile(@Valid @RequestBody IngestPathBody body) {
        return prepare.ingestJobFile(Path.of(body.path()), progress(body.taskId()));
    }

    @PostMapping("/prepare/job-text")
    public StoredJob ingestJobText(@Valid @RequestBody IngestJobTextBody body) {
        return prepare.ingestJobText(body.raw(), progress(body.taskId()));
    }

    @PostMapping("/prepare/job-synthesize")
    public StoredJob synthesizeJob(@Valid @RequestBody SynthesizeJobBody body) {
        return prepare.synthesizeJob(body.title(), body.tier(), body.level(), body.extra(),
                progress(body.taskId()));
    }

    @PostMapping("/prepare/diagnose")
    public StoredGap diagnose(@Valid @RequestBody DiagnoseBody body) {
        return prepare.diagnose(body.resumeId(), body.jobId(), body.refresh(),
                progress(body.taskId()));
    }
}

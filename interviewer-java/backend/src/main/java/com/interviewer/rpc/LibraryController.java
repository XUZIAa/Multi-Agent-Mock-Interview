package com.interviewer.rpc;

import com.interviewer.data.dto.StoredJob;
import com.interviewer.data.dto.StoredResume;
import com.interviewer.data.repository.LibraryRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "library")
public class LibraryController {

    private final LibraryRepository library;

    public LibraryController(LibraryRepository library) {
        this.library = library;
    }

    @GetMapping("/library/resumes")
    public List<StoredResume> resumes(
            @RequestParam(defaultValue = "30") @Min(1) @Max(200) int limit) {
        return library.listResumes(limit);
    }

    @GetMapping("/library/jobs")
    public List<StoredJob> jobs(
            @RequestParam(defaultValue = "30") @Min(1) @Max(200) int limit) {
        return library.listJobs(limit);
    }
}

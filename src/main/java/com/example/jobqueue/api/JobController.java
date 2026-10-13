package com.example.jobqueue.api;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Job> submit(@Valid @RequestBody SubmitJobRequest request) {
        Job job = service.submit(request);
        return ResponseEntity.created(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping("/{id}")
    public Job get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttempt> attempts(@PathVariable UUID id) {
        return service.attempts(id);
    }

    /** Cancel a PENDING job. 404 if unknown, 409 if it is no longer PENDING. */
    @DeleteMapping("/{id}")
    public Job cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    /** Re-queue a DEAD job. 404 if unknown, 409 if it is not DEAD. */
    @PostMapping("/{id}/retry")
    public Job retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    /** The body stays a plain array; the total number of matches is in the X-Total-Count header. */
    @GetMapping
    public ResponseEntity<List<Job>> list(@RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(defaultValue = "50") int limit,
                                          @RequestParam(defaultValue = "0") int offset) {
        JobService.PagedJobs page = service.list(status, type, limit, offset);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.total()))
                .body(page.items());
    }
}

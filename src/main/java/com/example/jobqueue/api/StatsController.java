package com.example.jobqueue.api;

import com.example.jobqueue.domain.JobStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final JobService service;

    public StatsController(JobService service) {
        this.service = service;
    }

    @GetMapping
    public JobStats stats() {
        return service.stats();
    }
}

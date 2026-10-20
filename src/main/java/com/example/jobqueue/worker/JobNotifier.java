package com.example.jobqueue.worker;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Holds one dedicated PostgreSQL connection that LISTENs on the "jobqueue_jobs" channel. Database triggers send a
 * notification whenever a job may have become runnable, and every idle worker thread is woken immediately instead of
 * waiting for its next poll.
 *
 * This is an optimisation, not a requirement: if the connection cannot be made (for example behind a pooler such
 * as PgBouncer in transaction mode) or drops, workers fall back to plain polling and the listener keeps retrying.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class JobNotifier {

    private static final Logger log = LoggerFactory.getLogger(JobNotifier.class);
    private static final String CHANNEL = "jobqueue_jobs";

    private final JdbcConnectionDetails database;
    private final WakeSignal wake;
    private final WorkerProperties props;

    private volatile boolean running;
    private volatile boolean listening;

    public JobNotifier(JdbcConnectionDetails database, WakeSignal wake, WorkerProperties props) {
        this.database = database;
        this.wake = wake;
        this.props = props;
    }

    /** True while the LISTEN connection is up. */
    public boolean isListening() {
        return listening;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.listenEnabled()) {
            log.info("LISTEN/NOTIFY wake-ups are disabled; workers poll every {} ms", props.pollIntervalMs());
            return;
        }
        running = true;
        Thread thread = new Thread(this::listenLoop, "job-notifier");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void listenLoop() {
        while (running) {
            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                PGConnection postgres = connection.unwrap(PGConnection.class);
                listening = true;
                wake.signal(); // look for work once, in case something arrived while we were not listening
                log.info("Listening for job notifications on channel '{}'", CHANNEL);
                while (running) {
                    PGNotification[] notifications = postgres.getNotifications(5000);
                    if (notifications != null && notifications.length > 0) {
                        wake.signal();
                    } else if (!connection.isValid(3)) {
                        throw new SQLException("connection is no longer valid");
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Job notification listener lost its connection ({}); falling back to polling", e.toString());
                }
            } finally {
                listening = false;
            }
            if (running) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

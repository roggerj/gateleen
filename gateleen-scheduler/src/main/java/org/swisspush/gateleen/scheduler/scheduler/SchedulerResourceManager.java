package org.swisspush.gateleen.scheduler.scheduler;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.swisspush.gateleen.core.monitoring.MonitoringHandler;
import org.swisspush.gateleen.core.storage.ResourceStorage;

import java.util.List;
import java.util.Map;

/**
 * @author https://github.com/lbovet [Laurent Bovet]
 */
public class SchedulerResourceManager {

    private static final String UPDATE_ADDRESS = "gateleen.schedulers-updated";
    private String schedulersUri;
    private ResourceStorage storage;
    private Logger log = LoggerFactory.getLogger(SchedulerResourceManager.class);
    private Vertx vertx;
    private List<Scheduler> schedulers;
    private Map<String, Object> properties;
    private SchedulerFactory schedulerFactory;

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri) {
        this(vertx, redisClient, storage, monitoringHandler, schedulersUri, null);
    }

    public SchedulerResourceManager(Vertx vertx, RedisClient redisClient, final ResourceStorage storage, MonitoringHandler monitoringHandler, String schedulersUri, Map<String,Object> props) {
        this.vertx = vertx;
        this.storage = storage;
        this.schedulersUri = schedulersUri;
        this.properties = props;

        this.schedulerFactory = new SchedulerFactory(properties, vertx, redisClient, monitoringHandler);

        updateSchedulers();

        // Receive update notifications
        vertx.eventBus().consumer(UPDATE_ADDRESS, (Handler<Message<Boolean>>) event -> updateSchedulers());
    }

    private void updateSchedulers() {
        storage.get(schedulersUri, buffer -> {
            if (buffer != null) {
                try {
                    updateSchedulers(buffer);
                } catch (IllegalArgumentException e) {
                    log.error("Could not configure schedulers", e);
                }
            } else {
                log.info("No schedulers configured");
            }
        });
    }

    private void updateSchedulers(Buffer buffer) {
        stopSchedulers();
        try {
            schedulers = schedulerFactory.parseSchedulers(buffer);
        } catch(Exception e) {
            log.error("Could not parse schedulers", e);
        } finally {
            vertx.setTimer(2000, aLong -> startSchedulers());
        }
    }

    public boolean handleSchedulerResource(final HttpServerRequest request) {
        if (request.uri().equals(schedulersUri) && HttpMethod.PUT == request.method()) {
            request.bodyHandler(buffer -> {
                try {
                    schedulerFactory.parseSchedulers(buffer);
                } catch (Exception e) {
                    log.warn("Could not parse schedulers", e);
                    request.response().setStatusCode(400);
                    request.response().setStatusMessage("Bad Request");
                    request.response().end(e.getMessage()+(e.getCause()!=null ? "\n"+e.getCause().getMessage():""));
                    return;
                }
                storage.put(schedulersUri, buffer, status -> {
                    if (status == 200) {
                        vertx.eventBus().publish(UPDATE_ADDRESS, true);
                    } else {
                        request.response().setStatusCode(status);
                    }
                    request.response().end();
                });
            });
            return true;
        }

        if (request.uri().equals(schedulersUri) && HttpMethod.DELETE == request.method()) {
            stopSchedulers();
        }
        return false;
    }

    private void startSchedulers() {
        if(schedulers != null) {
            schedulers.forEach(Scheduler::start);
        }
    }

    private void stopSchedulers() {
        if(schedulers != null) {
            schedulers.forEach(Scheduler::stop);
        }
    }
}

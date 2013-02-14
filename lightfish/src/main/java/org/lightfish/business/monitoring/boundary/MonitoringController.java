/*
 Copyright 2012 Adam Bien, adam-bien.com

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package org.lightfish.business.monitoring.boundary;

import java.util.ArrayList;
import org.lightfish.business.logging.Log;
import org.lightfish.business.monitoring.control.SnapshotProvider;
import org.lightfish.business.monitoring.entity.Snapshot;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.*;
import javax.enterprise.event.Event;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 *
 * @author Adam Bien, blog.adam-bien.com
 */
@Singleton
@Path("snapshots")
@Produces(MediaType.APPLICATION_JSON)
public class MonitoringController {

    public static final String COMBINED_SNAPSHOT_NAME = "__all__";
    @Inject
    private Log LOG;
    @Inject
    SnapshotProvider dataProvider;
    @PersistenceContext
    EntityManager em;
    @Inject
    @Severity(Severity.Level.HEARTBEAT)
    Event<Snapshot> heartBeat;
    @Inject
    Instance<String[]> serverInstances;
    @Resource
    TimerService timerService;
    private Timer timer;
    @Inject
    private Instance<Integer> interval;

    public void startTimer() {
        ScheduleExpression expression = new ScheduleExpression();
        expression.minute("*").second("*/" + interval.get()).hour("*");
        this.timer = this.timerService.createCalendarTimer(expression);
    }

    @Timeout
    public void gatherAndPersist() {
        String[] serverInstancesName = serverInstances.get();
        List<Snapshot> snapshots = new ArrayList<>(serverInstancesName.length);
        try {
            for (String instanceName : serverInstancesName) {
                Snapshot current = dataProvider.fetchSnapshot(instanceName);
                current.setInstanceName(instanceName);
                snapshots.add(current);
            }
        } catch (Exception ex) {
            LOG.error("Could not retrieve snapshot", ex);
            return;
        }
        
        snapshots.add(combineSnapshots(snapshots));
        
        for (Snapshot current : snapshots) {
            em.persist(current);
        }
        for (Snapshot current : snapshots) {
            try {
                heartBeat.fire(current);
            } catch (Exception e) {
                LOG.error("Cannot fire heartbeat", e);
            }
        }

        LOG.info(".");
    }

    private Snapshot combineSnapshots(List<Snapshot> snapshots) {

        long usedHeapSize = 0l;
        int threadCount = 0;
        int peakThreadCount = 0;
        int totalErrors = 0;
        int currentThreadBusy = 0;
        int committedTX = 0;
        int rolledBackTX = 0;
        int queuedConnections = 0;
        int activeSessions = 0;
        int expiredSessions = 0;
        for (Snapshot current : snapshots) {
            usedHeapSize += current.getUsedHeapSize();
            threadCount += current.getThreadCount();
            peakThreadCount += current.getPeakThreadCount();
            totalErrors += current.getTotalErrors();
            currentThreadBusy += current.getCurrentThreadBusy();
            committedTX += current.getCommittedTX();
            rolledBackTX += current.getRolledBackTX();
            queuedConnections += current.getQueuedConnections();
            activeSessions += current.getActiveSessions();
            expiredSessions += current.getExpiredSessions();
        }

        Snapshot combined = new Snapshot.Builder()
                .activeSessions(activeSessions)
                .committedTX(committedTX)
                .currentThreadBusy(currentThreadBusy)
                .expiredSessions(expiredSessions)
                .peakThreadCount(peakThreadCount)
                .queuedConnections(queuedConnections)
                .rolledBackTX(rolledBackTX)
                .threadCount(threadCount)
                .totalErrors(totalErrors)
                .usedHeapSize(usedHeapSize)
                .instanceName(COMBINED_SNAPSHOT_NAME)
                .build();

        return combined;
    }

    @GET
    public List<Snapshot> all() {
        CriteriaBuilder cb = this.em.getCriteriaBuilder();
        CriteriaQuery q = cb.createQuery();
        CriteriaQuery<Snapshot> select = q.select(q.from(Snapshot.class));
        return this.em.createQuery(select)
                .getResultList();

    }

    @PreDestroy
    public void stopTimer() {
        if (timer != null) {
            try {
                this.timer.cancel();
            } catch (Exception e) {
                LOG.error("Cannot cancel timer " + this.timer, e);
            } finally {
                this.timer = null;
            }
        }
    }

    public boolean isRunning() {
        return (this.timer != null);
    }
}

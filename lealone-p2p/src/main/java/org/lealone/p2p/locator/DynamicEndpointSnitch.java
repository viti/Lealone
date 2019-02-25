/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.p2p.locator;

import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.lealone.common.concurrent.ScheduledExecutors;
import org.lealone.net.NetEndpoint;
import org.lealone.p2p.config.ConfigDescriptor;
import org.lealone.p2p.net.MessagingService;
import org.lealone.p2p.server.P2pServer;
import org.lealone.p2p.util.Utils;

import com.yammer.metrics.stats.ExponentiallyDecayingSample;

/**
 * A dynamic snitch that sorts endpoints by latency with an adapted phi failure detector
 */
public class DynamicEndpointSnitch extends AbstractEndpointSnitch
        implements ILatencySubscriber, DynamicEndpointSnitchMBean {
    private static final double ALPHA = 0.75; // set to 0.75 to make EDS more biased to towards the newer values
    private static final int WINDOW_SIZE = 100;

    private final int UPDATE_INTERVAL_IN_MS = ConfigDescriptor.getDynamicUpdateInterval();
    private final int RESET_INTERVAL_IN_MS = ConfigDescriptor.getDynamicResetInterval();
    private final double BADNESS_THRESHOLD = ConfigDescriptor.getDynamicBadnessThreshold();

    // the score for a merged set of endpoints must be this much worse than the score for separate endpoints to
    // warrant not merging two ranges into a single range
    private final double RANGE_MERGING_PREFERENCE = 1.5;

    private String mbeanName;
    private boolean registered = false;

    private final ConcurrentHashMap<NetEndpoint, Double> scores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NetEndpoint, ExponentiallyDecayingSample> samples = new ConcurrentHashMap<>();

    public final IEndpointSnitch subsnitch;

    public DynamicEndpointSnitch(IEndpointSnitch snitch) {
        this(snitch, null);
    }

    public DynamicEndpointSnitch(IEndpointSnitch snitch, String instance) {
        mbeanName = Utils.getJmxObjectName("DynamicEndpointSnitch");
        if (instance != null)
            mbeanName += ",instance=" + instance;
        subsnitch = snitch;
        Runnable update = new Runnable() {
            @Override
            public void run() {
                updateScores();
            }
        };
        Runnable reset = new Runnable() {
            @Override
            public void run() {
                // we do this so that a host considered bad has a chance to recover, otherwise would we never try
                // to read from it, which would cause its score to never change
                reset();
            }
        };
        ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(update, UPDATE_INTERVAL_IN_MS, UPDATE_INTERVAL_IN_MS,
                TimeUnit.MILLISECONDS);
        ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(reset, RESET_INTERVAL_IN_MS, RESET_INTERVAL_IN_MS,
                TimeUnit.MILLISECONDS);
        registerMBean();
    }

    private void registerMBean() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(this, new ObjectName(mbeanName));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void unregisterMBean() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.unregisterMBean(new ObjectName(mbeanName));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void gossiperStarting() {
        subsnitch.gossiperStarting();
    }

    @Override
    public String getRack(NetEndpoint endpoint) {
        return subsnitch.getRack(endpoint);
    }

    @Override
    public String getDatacenter(NetEndpoint endpoint) {
        return subsnitch.getDatacenter(endpoint);
    }

    @Override
    public List<NetEndpoint> getSortedListByProximity(final NetEndpoint address, Collection<NetEndpoint> addresses) {
        List<NetEndpoint> list = new ArrayList<NetEndpoint>(addresses);
        sortByProximity(address, list);
        return list;
    }

    @Override
    public void sortByProximity(final NetEndpoint address, List<NetEndpoint> addresses) {
        assert address.equals(ConfigDescriptor.getLocalEndpoint()); // we only know about ourself
        if (BADNESS_THRESHOLD == 0) {
            sortByProximityWithScore(address, addresses);
        } else {
            sortByProximityWithBadness(address, addresses);
        }
    }

    private void sortByProximityWithScore(final NetEndpoint address, List<NetEndpoint> addresses) {
        super.sortByProximity(address, addresses);
    }

    private void sortByProximityWithBadness(final NetEndpoint address, List<NetEndpoint> addresses) {
        if (addresses.size() < 2)
            return;

        subsnitch.sortByProximity(address, addresses);
        ArrayList<Double> subsnitchOrderedScores = new ArrayList<>(addresses.size());
        for (NetEndpoint inet : addresses) {
            Double score = scores.get(inet);
            if (score == null)
                return;
            subsnitchOrderedScores.add(score);
        }

        // Sort the scores and then compare them (positionally) to the scores in the subsnitch order.
        // If any of the subsnitch-ordered scores exceed the optimal/sorted score by BADNESS_THRESHOLD, use
        // the score-sorted ordering instead of the subsnitch ordering.
        ArrayList<Double> sortedScores = new ArrayList<>(subsnitchOrderedScores);
        Collections.sort(sortedScores);

        Iterator<Double> sortedScoreIterator = sortedScores.iterator();
        for (Double subsnitchScore : subsnitchOrderedScores) {
            if (subsnitchScore > (sortedScoreIterator.next() * (1.0 + BADNESS_THRESHOLD))) {
                sortByProximityWithScore(address, addresses);
                return;
            }
        }
    }

    @Override
    public int compareEndpoints(NetEndpoint target, NetEndpoint a1, NetEndpoint a2) {
        Double scored1 = scores.get(a1);
        Double scored2 = scores.get(a2);

        if (scored1 == null) {
            scored1 = 0.0;
            receiveTiming(a1, 0);
        }

        if (scored2 == null) {
            scored2 = 0.0;
            receiveTiming(a2, 0);
        }

        if (scored1.equals(scored2))
            return subsnitch.compareEndpoints(target, a1, a2);
        if (scored1 < scored2)
            return -1;
        else
            return 1;
    }

    @Override
    public void receiveTiming(NetEndpoint host, long latency) // this is cheap
    {
        ExponentiallyDecayingSample sample = samples.get(host);
        if (sample == null) {
            ExponentiallyDecayingSample maybeNewSample = new ExponentiallyDecayingSample(WINDOW_SIZE, ALPHA);
            sample = samples.putIfAbsent(host, maybeNewSample);
            if (sample == null)
                sample = maybeNewSample;
        }
        sample.update(latency);
    }

    private void updateScores() // this is expensive
    {
        if (!P2pServer.instance.isStarted())
            return;
        if (!registered) {
            if (MessagingService.instance() != null) {
                MessagingService.instance().register(this);
                registered = true;
            }

        }
        double maxLatency = 1;
        // We're going to weight the latency for each host against the worst one we see, to
        // arrive at sort of a 'badness percentage' for them. First, find the worst for each:
        for (Map.Entry<NetEndpoint, ExponentiallyDecayingSample> entry : samples.entrySet()) {
            double mean = entry.getValue().getSnapshot().getMedian();
            if (mean > maxLatency)
                maxLatency = mean;
        }
        // now make another pass to do the weighting based on the maximums we found before
        for (Map.Entry<NetEndpoint, ExponentiallyDecayingSample> entry : samples.entrySet()) {
            double score = entry.getValue().getSnapshot().getMedian() / maxLatency;
            // finally, add the severity without any weighting,
            // since hosts scale this relative to their own load and the size of the task causing the severity.
            // "Severity" is basically a measure of compaction activity (lealone-3722).
            score += P2pServer.instance.getSeverity(entry.getKey());
            // lowest score (least amount of badness) wins.
            scores.put(entry.getKey(), score);
        }
    }

    private void reset() {
        for (ExponentiallyDecayingSample sample : samples.values())
            sample.clear();
    }

    @Override
    public Map<NetEndpoint, Double> getScores() {
        return scores;
    }

    @Override
    public int getUpdateInterval() {
        return UPDATE_INTERVAL_IN_MS;
    }

    @Override
    public int getResetInterval() {
        return RESET_INTERVAL_IN_MS;
    }

    @Override
    public double getBadnessThreshold() {
        return BADNESS_THRESHOLD;
    }

    @Override
    public String getSubsnitchClassName() {
        return subsnitch.getClass().getName();
    }

    @Override
    public List<Double> dumpTimings(String hostname) throws UnknownHostException {
        NetEndpoint host = NetEndpoint.getByName(hostname);
        ArrayList<Double> timings = new ArrayList<Double>();
        ExponentiallyDecayingSample sample = samples.get(host);
        if (sample != null) {
            for (double time : sample.getSnapshot().getValues())
                timings.add(time);
        }
        return timings;
    }

    @Override
    public void setSeverity(double severity) {
        P2pServer.instance.reportManualSeverity(severity);
    }

    @Override
    public double getSeverity() {
        return P2pServer.instance.getSeverity(ConfigDescriptor.getLocalEndpoint());
    }

    @Override
    public boolean isWorthMergingForRangeQuery(List<NetEndpoint> merged, List<NetEndpoint> l1, List<NetEndpoint> l2) {
        if (!subsnitch.isWorthMergingForRangeQuery(merged, l1, l2))
            return false;

        // skip checking scores in the single-node case
        if (l1.size() == 1 && l2.size() == 1 && l1.get(0).equals(l2.get(0)))
            return true;

        // Make sure we return the subsnitch decision (i.e true if we're here) if we lack too much scores
        double maxMerged = maxScore(merged);
        double maxL1 = maxScore(l1);
        double maxL2 = maxScore(l2);
        if (maxMerged < 0 || maxL1 < 0 || maxL2 < 0)
            return true;

        return maxMerged <= (maxL1 + maxL2) * RANGE_MERGING_PREFERENCE;
    }

    // Return the max score for the endpoint in the provided list, or -1.0 if no node have a score.
    private double maxScore(List<NetEndpoint> endpoints) {
        double maxScore = -1.0;
        for (NetEndpoint endpoint : endpoints) {
            Double score = scores.get(endpoint);
            if (score == null)
                continue;

            if (score > maxScore)
                maxScore = score;
        }
        return maxScore;
    }
}

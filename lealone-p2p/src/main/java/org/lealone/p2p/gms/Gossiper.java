/*
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
package org.lealone.p2p.gms;

import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.lealone.common.concurrent.DebuggableScheduledThreadPoolExecutor;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.JVMStabilityInspector;
import org.lealone.net.NetEndpoint;
import org.lealone.p2p.concurrent.MetricsEnabledThreadPoolExecutor;
import org.lealone.p2p.concurrent.Stage;
import org.lealone.p2p.concurrent.StageManager;
import org.lealone.p2p.config.Config;
import org.lealone.p2p.config.ConfigDescriptor;
import org.lealone.p2p.net.IAsyncCallback;
import org.lealone.p2p.net.MessageIn;
import org.lealone.p2p.net.MessageOut;
import org.lealone.p2p.net.MessagingService;
import org.lealone.p2p.net.Verb;
import org.lealone.p2p.server.P2pServer;
import org.lealone.p2p.util.Pair;
import org.lealone.p2p.util.Uninterruptibles;
import org.lealone.p2p.util.Utils;

/**
 * This module is responsible for Gossiping information for the local endpoint. This abstraction
 * maintains the list of live and dead endpoints. Periodically i.e. every 1 second this module
 * chooses a random node and initiates a round of Gossip with it. A round of Gossip involves 3
 * rounds of messaging. For instance if node A wants to initiate a round of Gossip with node B
 * it starts off by sending node B a GossipDigestSynMessage. Node B on receipt of this message
 * sends node A a GossipDigestAckMessage. On receipt of this message node A sends node B a
 * GossipDigestAck2Message which completes a round of Gossip. This module as and when it hears one
 * of the three above mentioned messages updates the Failure Detector with the liveness information.
 * Upon hearing a GossipShutdownMessage, this module will instantly mark the remote node as down in
 * the Failure Detector.
 */
public class Gossiper implements IFailureDetectionEventListener, GossiperMBean {
    private static final Logger logger = LoggerFactory.getLogger(Gossiper.class);

    private static final DebuggableScheduledThreadPoolExecutor executor = new DebuggableScheduledThreadPoolExecutor(
            "GossipTasks");
    private static final ReentrantLock taskLock = new ReentrantLock();

    private static final List<String> DEAD_STATES = Arrays.asList(VersionedValue.REMOVING_TOKEN,
            VersionedValue.REMOVED_TOKEN, VersionedValue.STATUS_LEFT, VersionedValue.HIBERNATE);

    private static final int RING_DELAY = getRingDelay(); // delay after which we assume ring has stablized

    private static int getRingDelay() {
        String newDelay = Config.getProperty("ring.delay.ms");
        if (newDelay != null) {
            logger.info("Overriding RING_DELAY to {}ms", newDelay);
            return Integer.parseInt(newDelay);
        } else
            return 30 * 1000;
    }

    // Maximum difference in generation and version values we are willing to accept about a peer
    private static final long MAX_GENERATION_DIFFERENCE = 86400 * 365;
    private static final int QUARANTINE_DELAY = RING_DELAY * 2;
    // half of QUARATINE_DELAY, to ensure justRemovedEndpoints has enough leeway to prevent re-gossip
    private static final long FAT_CLIENT_TIMEOUT = QUARANTINE_DELAY / 2;
    private static final long A_VERY_LONG_TIME = 259200 * 1000; // 3 days

    public final static int INTERVAL_IN_MILLIS = 1000;

    public final static Gossiper instance = new Gossiper();

    private ScheduledFuture<?> scheduledGossipTask;

    private final Random random = new Random();
    private final Comparator<NetEndpoint> inetcomparator = new Comparator<NetEndpoint>() {
        @Override
        public int compare(NetEndpoint addr1, NetEndpoint addr2) {
            return addr1.compareTo(addr2);
        }
    };

    /* subscribers for interest in EndpointState change */
    private final List<IEndpointStateChangeSubscriber> subscribers = new CopyOnWriteArrayList<>();

    /* live member set */
    private final Set<NetEndpoint> liveEndpoints = new ConcurrentSkipListSet<>(inetcomparator);

    /* unreachable member set */
    private final Map<NetEndpoint, Long> unreachableEndpoints = new ConcurrentHashMap<>();

    /* initial seeds for joining the cluster */
    private final Set<NetEndpoint> seeds = new ConcurrentSkipListSet<>(inetcomparator);

    /* map where key is the endpoint and value is the state associated with the endpoint */
    final ConcurrentMap<NetEndpoint, EndpointState> endpointStateMap = new ConcurrentHashMap<>();

    /* map where key is endpoint and value is timestamp when this endpoint was removed from
     * gossip. We will ignore any gossip regarding these endpoints for QUARANTINE_DELAY time
     * after removal to prevent nodes from falsely reincarnating during the time when removal
     * gossip gets propagated to all nodes */
    private final Map<NetEndpoint, Long> justRemovedEndpoints = new ConcurrentHashMap<>();

    private final Map<NetEndpoint, Long> expireTimeEndpointMap = new ConcurrentHashMap<>();

    private boolean inShadowRound = false;

    private volatile long lastProcessedMessageAt = System.currentTimeMillis();

    private class GossipTask implements Runnable {
        @Override
        public void run() {
            try {
                taskLock.lock();

                /* Update the local heartbeat counter. */
                endpointStateMap.get(ConfigDescriptor.getLocalEndpoint()).getHeartBeatState().updateHeartBeat();
                if (logger.isTraceEnabled())
                    logger.trace("My heartbeat is now {}", endpointStateMap.get(ConfigDescriptor.getLocalEndpoint())
                            .getHeartBeatState().getHeartBeatVersion());
                final List<GossipDigest> gDigests = new ArrayList<>();
                Gossiper.instance.makeRandomGossipDigest(gDigests);

                if (gDigests.size() > 0) {
                    GossipDigestSyn digestSynMessage = new GossipDigestSyn(ConfigDescriptor.getClusterName(), gDigests);
                    MessageOut<GossipDigestSyn> message = new MessageOut<>(Verb.GOSSIP_DIGEST_SYN, digestSynMessage);
                    /* Gossip to some random live member */
                    boolean gossipedToSeed = doGossipToLiveMember(message);

                    /* Gossip to some unreachable member with some probability to check if he is back up */
                    doGossipToUnreachableMember(message);

                    /* Gossip to a seed if we did not do so above, or we have seen less nodes
                       than there are seeds.  This prevents partitions where each group of nodes
                       is only gossiping to a subset of the seeds.
                    
                       The most straightforward check would be to check that all the seeds have been
                       verified either as live or unreachable.  To avoid that computation each round,
                       we reason that:
                    
                       either all the live nodes are seeds, in which case non-seeds that come online
                       will introduce themselves to a member of the ring by definition,
                    
                       or there is at least one non-seed node in the list, in which case eventually
                       someone will gossip to it, and then do a gossip to a random seed from the
                       gossipedToSeed check.
                    
                       See Cassandra-150 for more exposition. */
                    if (!gossipedToSeed || liveEndpoints.size() < seeds.size())
                        doGossipToSeed(message);

                    doStatusCheck();
                }
            } catch (Exception e) {
                JVMStabilityInspector.inspectThrowable(e);
                logger.error("Gossip error", e);
            } finally {
                taskLock.unlock();
            }
        }
    }

    private Gossiper() {
        // register with the Failure Detector for receiving Failure detector events
        FailureDetector.instance.registerFailureDetectionEventListener(this);

        // Register this instance with JMX
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(this, new ObjectName(Utils.getJmxObjectName("Gossiper")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start(int generationNumber) {
        start(generationNumber, new HashMap<ApplicationState, VersionedValue>());
    }

    /**
     * Start the gossiper with the generation number, preloading the map of application states before starting
     */
    public void start(int generationNbr, Map<ApplicationState, VersionedValue> preloadLocalStates) {
        buildSeedsList();
        // initialize the heartbeat state for this localEndpoint
        maybeInitializeLocalState(generationNbr);
        EndpointState localState = endpointStateMap.get(ConfigDescriptor.getLocalEndpoint());
        for (Map.Entry<ApplicationState, VersionedValue> entry : preloadLocalStates.entrySet())
            localState.addApplicationState(entry.getKey(), entry.getValue());

        // notify snitches that Gossiper is about to start
        ConfigDescriptor.getEndpointSnitch().gossiperStarting();
        if (logger.isTraceEnabled())
            logger.trace("gossip started with generation {}", localState.getHeartBeatState().getGeneration());

        scheduledGossipTask = executor.scheduleWithFixedDelay(new GossipTask(), Gossiper.INTERVAL_IN_MILLIS,
                Gossiper.INTERVAL_IN_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void buildSeedsList() {
        NetEndpoint local = ConfigDescriptor.getLocalEndpoint();
        for (NetEndpoint seed : ConfigDescriptor.getSeeds()) {
            if (!seed.equals(local)) {
                seeds.add(seed);
            }
        }
    }

    // initialize local HB state if needed, i.e., if gossiper has never been started before.
    private void maybeInitializeLocalState(int generationNbr) {
        HeartBeatState hbState = new HeartBeatState(generationNbr);
        EndpointState localState = new EndpointState(hbState);
        endpointStateMap.putIfAbsent(ConfigDescriptor.getLocalEndpoint(), localState);
    }

    /**
     * The gossip digest is built based on randomization
     * rather than just looping through the collection of live endpoints.
     *
     * @param gDigests list of Gossip Digests.
     */
    private void makeRandomGossipDigest(List<GossipDigest> gDigests) {
        EndpointState epState;
        int generation = 0;
        int maxVersion = 0;

        // local epstate will be part of endpointStateMap
        List<NetEndpoint> endpoints = new ArrayList<>(endpointStateMap.keySet());
        Collections.shuffle(endpoints, random);
        for (NetEndpoint endpoint : endpoints) {
            epState = endpointStateMap.get(endpoint);
            if (epState != null) {
                generation = epState.getHeartBeatState().getGeneration();
                maxVersion = getMaxEndpointStateVersion(epState);
            }
            gDigests.add(new GossipDigest(endpoint, generation, maxVersion));
        }

        if (logger.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (GossipDigest gDigest : gDigests) {
                sb.append(gDigest);
                sb.append(" ");
            }
            logger.trace("Gossip Digests are : {}", sb);
        }
    }

    /* Sends a Gossip message to a live member and returns true if the recipient was a seed */
    private boolean doGossipToLiveMember(MessageOut<GossipDigestSyn> message) {
        if (liveEndpoints.isEmpty())
            return false;
        return sendGossip(message, liveEndpoints);
    }

    /* Sends a Gossip message to an unreachable member */
    private void doGossipToUnreachableMember(MessageOut<GossipDigestSyn> message) {
        double liveEndpointCount = liveEndpoints.size();
        double unreachableEndpointCount = unreachableEndpoints.size();
        if (unreachableEndpointCount > 0) {
            /* based on some probability */
            double prob = unreachableEndpointCount / (liveEndpointCount + 1);
            double randDbl = random.nextDouble();
            if (randDbl < prob)
                sendGossip(message, unreachableEndpoints.keySet());
        }
    }

    /* Gossip to a seed for facilitating partition healing */
    private void doGossipToSeed(MessageOut<GossipDigestSyn> prod) {
        int size = seeds.size();
        if (size > 0) {
            if (size == 1 && seeds.contains(ConfigDescriptor.getLocalEndpoint())) {
                return;
            }

            if (liveEndpoints.isEmpty()) {
                sendGossip(prod, seeds);
            } else {
                /* Gossip with the seed with some probability. */
                double probability = seeds.size() / (double) (liveEndpoints.size() + unreachableEndpoints.size());
                double randDbl = random.nextDouble();
                if (randDbl <= probability)
                    sendGossip(prod, seeds);
            }
        }
    }

    /**
     * Returns true if the chosen target was also a seed. False otherwise
     *
     * @param message
     * @param epSet   a set of endpoint from which a random endpoint is chosen.
     * @return true if the chosen endpoint is also a seed.
     */
    private boolean sendGossip(MessageOut<GossipDigestSyn> message, Set<NetEndpoint> epSet) {
        List<NetEndpoint> liveEndpoints = new ArrayList<>(epSet);

        int size = liveEndpoints.size();
        if (size < 1)
            return false;
        /* Generate a random number from 0 -> size */
        int index = (size == 1) ? 0 : random.nextInt(size);
        NetEndpoint to = liveEndpoints.get(index);
        if (logger.isTraceEnabled())
            logger.trace("Sending a GossipDigestSyn to {} ...", to);
        MessagingService.instance().sendOneWay(message, to);
        return seeds.contains(to);
    }

    private void doStatusCheck() {
        if (logger.isTraceEnabled())
            logger.trace("Performing status check ...");

        long now = System.currentTimeMillis();
        long nowNano = System.nanoTime();

        long pending = ((MetricsEnabledThreadPoolExecutor) StageManager.getStage(Stage.GOSSIP)).getPendingTasks();
        if (pending > 0 && lastProcessedMessageAt < now - 1000) {
            // if some new messages just arrived, give the executor some time to work on them
            Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);

            // still behind? something's broke
            if (lastProcessedMessageAt < now - 1000) {
                logger.warn("Gossip stage has {} pending tasks; skipping status check (no nodes will be marked down)",
                        pending);
                return;
            }
        }

        Set<NetEndpoint> eps = endpointStateMap.keySet();
        for (NetEndpoint endpoint : eps) {
            if (endpoint.equals(ConfigDescriptor.getLocalEndpoint()))
                continue;

            FailureDetector.instance.interpret(endpoint);
            EndpointState epState = endpointStateMap.get(endpoint);
            if (epState != null) {
                // check if this is a fat client. fat clients are removed automatically from
                // gossip after FatClientTimeout. Do not remove dead states here.
                if (isGossipOnlyMember(endpoint) && !justRemovedEndpoints.containsKey(endpoint)
                        && TimeUnit.NANOSECONDS.toMillis(nowNano - epState.getUpdateTimestamp()) > FAT_CLIENT_TIMEOUT) {
                    logger.info("FatClient {} has been silent for {}ms, removing from gossip", endpoint,
                            FAT_CLIENT_TIMEOUT);
                    removeEndpoint(endpoint); // will put it in justRemovedEndpoints to respect quarantine delay
                    evictFromMembership(endpoint); // can get rid of the state immediately
                }

                // check for dead state removal
                long expireTime = getExpireTimeForEndpoint(endpoint);
                if (!epState.isAlive() && (now > expireTime)
                        && (!P2pServer.instance.getTopologyMetaData().isMember(endpoint))) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("time is expiring for endpoint : {} ({})", endpoint, expireTime);
                    }
                    evictFromMembership(endpoint);
                }
            }
        }

        if (!justRemovedEndpoints.isEmpty()) {
            for (Entry<NetEndpoint, Long> entry : justRemovedEndpoints.entrySet()) {
                if ((now - entry.getValue()) > QUARANTINE_DELAY) {
                    if (logger.isDebugEnabled())
                        logger.debug("{} elapsed, {} gossip quarantine over", QUARANTINE_DELAY, entry.getKey());
                    justRemovedEndpoints.remove(entry.getKey());
                }
            }
        }
    }

    public void setLastProcessedMessageAt(long timeInMillis) {
        this.lastProcessedMessageAt = timeInMillis;
    }

    /**
     * Register for interesting state changes.
     *
     * @param subscriber module which implements the IEndpointStateChangeSubscriber
     */
    public void register(IEndpointStateChangeSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * Unregister interest for state changes.
     *
     * @param subscriber module which implements the IEndpointStateChangeSubscriber
     */
    public void unregister(IEndpointStateChangeSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    public Set<NetEndpoint> getLiveMembers() {
        Set<NetEndpoint> liveMembers = new HashSet<>(liveEndpoints);
        if (!liveMembers.contains(ConfigDescriptor.getLocalEndpoint()))
            liveMembers.add(ConfigDescriptor.getLocalEndpoint());
        return liveMembers;
    }

    /**
     * @return a list of unreachable gossip participants, including fat clients
     */
    public Set<NetEndpoint> getUnreachableMembers() {
        return unreachableEndpoints.keySet();
    }

    // /**
    // * @return a list of unreachable token owners
    // */
    // public Set<NetEndpoint> getUnreachableTokenOwners() {
    // Set<NetEndpoint> tokenOwners = new HashSet<>();
    // for (NetEndpoint endpoint : unreachableEndpoints.keySet()) {
    // if (P2pServer.instance.getTopologyMetaData().isMember(endpoint))
    // tokenOwners.add(endpoint);
    // }
    //
    // return tokenOwners;
    // }

    /**
     * This method is part of IFailureDetectionEventListener interface. This is invoked
     * by the Failure Detector when it convicts an end point.
     *
     * @param endpoint end point that is convicted.
     */
    @Override
    public void convict(NetEndpoint endpoint, double phi) {
        EndpointState epState = endpointStateMap.get(endpoint);
        if (epState == null)
            return;
        if (epState.isAlive() && !isDeadState(epState)) {
            markDead(endpoint, epState);
        } else
            epState.markDead();
    }

    /**
     * Return either: the greatest heartbeat or application state
     *
     * @param epState
     * @return
     */
    int getMaxEndpointStateVersion(EndpointState epState) {
        int maxVersion = epState.getHeartBeatState().getHeartBeatVersion();
        for (VersionedValue value : epState.getApplicationStateMap().values())
            maxVersion = Math.max(maxVersion, value.version);
        return maxVersion;
    }

    /**
     * Removes the endpoint from gossip completely
     *
     * @param endpoint endpoint to be removed from the current membership.
     */
    private void evictFromMembership(NetEndpoint endpoint) {
        unreachableEndpoints.remove(endpoint);
        endpointStateMap.remove(endpoint);
        expireTimeEndpointMap.remove(endpoint);
        quarantineEndpoint(endpoint);
        if (logger.isDebugEnabled())
            logger.debug("evicting {} from gossip", endpoint);
    }

    /**
     * Removes the endpoint from Gossip but retains endpoint state
     */
    public void removeEndpoint(NetEndpoint endpoint) {
        // do subscribers first so anything in the subscriber that depends on gossiper state won't get confused
        for (IEndpointStateChangeSubscriber subscriber : subscribers)
            subscriber.onRemove(endpoint);

        if (seeds.contains(endpoint)) {
            buildSeedsList();
            seeds.remove(endpoint);
            logger.info("removed {} from seeds, updated seeds list = {}", endpoint, seeds);
        }

        liveEndpoints.remove(endpoint);
        unreachableEndpoints.remove(endpoint);
        // do not remove endpointState until the quarantine expires
        FailureDetector.instance.remove(endpoint);
        MessagingService.instance().removeVersion(endpoint);
        quarantineEndpoint(endpoint);
        MessagingService.instance().removeConnection(endpoint);
        if (logger.isDebugEnabled())
            logger.debug("removing endpoint {}", endpoint);
    }

    /**
     * Quarantines the endpoint for QUARANTINE_DELAY
     *
     * @param endpoint
     */
    private void quarantineEndpoint(NetEndpoint endpoint) {
        quarantineEndpoint(endpoint, System.currentTimeMillis());
    }

    /**
     * Quarantines the endpoint until quarantineExpiration + QUARANTINE_DELAY
     *
     * @param endpoint
     * @param quarantineExpiration
     */
    private void quarantineEndpoint(NetEndpoint endpoint, long quarantineExpiration) {
        justRemovedEndpoints.put(endpoint, quarantineExpiration);
    }

    /**
     * Quarantine endpoint specifically for replacement purposes.
     * @param endpoint
     */
    public void replacementQuarantine(NetEndpoint endpoint) {
        // remember, quarantineEndpoint will effectively already add QUARANTINE_DELAY, so this is 2x
        quarantineEndpoint(endpoint, System.currentTimeMillis() + QUARANTINE_DELAY);
    }

    /**
     * Remove the Endpoint and evict immediately, to avoid gossiping about this node.
     * This should only be called when a token is taken over by a new IP address.
     *
     * @param endpoint The endpoint that has been replaced
     */
    // TODO 没用到，考虑删除
    public void replacedEndpoint(NetEndpoint endpoint) {
        removeEndpoint(endpoint);
        evictFromMembership(endpoint);
        replacementQuarantine(endpoint);
    }

    /**
     * This method will begin removing an existing endpoint from the cluster by spoofing its state
     * This should never be called unless this coordinator has had 'removenode' invoked
     *
     * @param endpoint    - the endpoint being removed
     * @param hostId      - the ID of the host being removed
     * @param localHostId - my own host ID for replication coordination
     */
    // TODO 没用到，考虑删除
    public void advertiseRemoving(NetEndpoint endpoint, UUID hostId, UUID localHostId) {
        EndpointState epState = endpointStateMap.get(endpoint);
        // remember this node's generation
        int generation = epState.getHeartBeatState().getGeneration();
        logger.info("Removing host: {}", hostId);
        logger.info("Sleeping for {}ms to ensure {} does not change", RING_DELAY, endpoint);
        Uninterruptibles.sleepUninterruptibly(RING_DELAY, TimeUnit.MILLISECONDS);
        // make sure it did not change
        epState = endpointStateMap.get(endpoint);
        if (epState.getHeartBeatState().getGeneration() != generation)
            throw new RuntimeException("Endpoint " + endpoint + " generation changed while trying to remove it");
        // update the other node's generation to mimic it as if it had changed it itself
        logger.info("Advertising removal for {}", endpoint);
        epState.updateTimestamp(); // make sure we don't evict it too soon
        epState.getHeartBeatState().forceNewerGenerationUnsafe();
        epState.addApplicationState(ApplicationState.STATUS, P2pServer.valueFactory.removingNonlocal(hostId));
        epState.addApplicationState(ApplicationState.REMOVAL_COORDINATOR,
                P2pServer.valueFactory.removalCoordinator(localHostId));
        endpointStateMap.put(endpoint, epState);
    }

    /**
     * Do not call this method unless you know what you are doing.
     * It will try extremely hard to obliterate any endpoint from the ring,
     * even if it does not know about it.
     *
     * @param address
     * @throws UnknownHostException
     */
    @Override
    public void assassinateEndpoint(String address) throws UnknownHostException {
        NetEndpoint endpoint = NetEndpoint.getByName(address);
        EndpointState epState = endpointStateMap.get(endpoint);
        logger.warn("Assassinating {} via gossip", endpoint);

        if (epState == null) {
            epState = new EndpointState(new HeartBeatState((int) ((System.currentTimeMillis() + 60000) / 1000), 9999));
        } else {
            int generation = epState.getHeartBeatState().getGeneration();
            int heartbeat = epState.getHeartBeatState().getHeartBeatVersion();
            logger.info("Sleeping for {}ms to ensure {} does not change", RING_DELAY, endpoint);
            Uninterruptibles.sleepUninterruptibly(RING_DELAY, TimeUnit.MILLISECONDS);
            // make sure it did not change
            EndpointState newState = endpointStateMap.get(endpoint);
            if (newState == null)
                logger.warn("Endpoint {} disappeared while trying to assassinate, continuing anyway", endpoint);
            else if (newState.getHeartBeatState().getGeneration() != generation)
                throw new RuntimeException(
                        "Endpoint still alive: " + endpoint + " generation changed while trying to assassinate it");
            else if (newState.getHeartBeatState().getHeartBeatVersion() != heartbeat)
                throw new RuntimeException(
                        "Endpoint still alive: " + endpoint + " heartbeat changed while trying to assassinate it");
            epState.updateTimestamp(); // make sure we don't evict it too soon
            epState.getHeartBeatState().forceNewerGenerationUnsafe();
        }

        // do not pass go, do not collect 200 dollars, just gtfo
        epState.addApplicationState(ApplicationState.STATUS, P2pServer.valueFactory.left(null, computeExpireTime()));
        handleMajorStateChange(endpoint, epState);
        Uninterruptibles.sleepUninterruptibly(INTERVAL_IN_MILLIS * 4, TimeUnit.MILLISECONDS);
        logger.warn("Finished assassinating {}", endpoint);
    }

    @Override
    public long getEndpointDowntime(String address) throws UnknownHostException {
        NetEndpoint ep = NetEndpoint.getByName(address);
        Long downtime = unreachableEndpoints.get(ep);
        if (downtime != null)
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downtime);
        else
            return 0L;
    }

    @Override
    public int getCurrentGenerationNumber(String address) throws UnknownHostException {
        NetEndpoint ep = NetEndpoint.getByName(address);
        return endpointStateMap.get(ep).getHeartBeatState().getGeneration();
    }

    public boolean isKnownEndpoint(NetEndpoint endpoint) {
        return endpointStateMap.containsKey(endpoint);
    }

    public boolean isGossipOnlyMember(NetEndpoint endpoint) {
        EndpointState epState = endpointStateMap.get(endpoint);
        if (epState == null) {
            return false;
        }
        return !isDeadState(epState) && !P2pServer.instance.getTopologyMetaData().isMember(endpoint);
    }

    protected long getExpireTimeForEndpoint(NetEndpoint endpoint) {
        /* default expireTime is aVeryLongTime */
        Long storedTime = expireTimeEndpointMap.get(endpoint);
        return storedTime == null ? computeExpireTime() : storedTime;
    }

    public EndpointState getEndpointState(NetEndpoint ep) {
        return endpointStateMap.get(ep);
    }

    // removes ALL endpoint states; should only be called after shadow gossip
    public void resetEndpointStateMap() {
        endpointStateMap.clear();
        unreachableEndpoints.clear();
        liveEndpoints.clear();
    }

    public Set<Entry<NetEndpoint, EndpointState>> getEndpointStates() {
        return endpointStateMap.entrySet();
    }

    public boolean usesHostId(NetEndpoint endpoint) {
        if (MessagingService.instance().knowsVersion(endpoint))
            return true;
        else if (getEndpointState(endpoint).getApplicationState(ApplicationState.NET_VERSION) != null)
            return true;
        return false;
    }

    public String getHostId(NetEndpoint endpoint) {
        if (!usesHostId(endpoint))
            throw new RuntimeException("Host " + endpoint + " does not use new-style tokens!");
        return getEndpointState(endpoint).getApplicationState(ApplicationState.HOST_ID).value;
    }

    public NetEndpoint getTcpEndpoint(NetEndpoint endpoint) {
        if (!usesHostId(endpoint))
            throw new RuntimeException("Host " + endpoint + " does not use new-style tokens!");
        return NetEndpoint
                .createP2P(getEndpointState(endpoint).getApplicationState(ApplicationState.TCP_ENDPOINT).value);
    }

    public String getLoad(NetEndpoint endpoint) {
        if (!usesHostId(endpoint))
            throw new RuntimeException("Host " + endpoint + " does not use new-style tokens!");
        return getEndpointState(endpoint).getApplicationState(ApplicationState.LOAD).value;
    }

    EndpointState getStateForVersionBiggerThan(NetEndpoint forEndpoint, int version) {
        EndpointState epState = endpointStateMap.get(forEndpoint);
        EndpointState reqdEndpointState = null;

        if (epState != null) {
            /*
             * Here we try to include the Heart Beat state only if it is
             * greater than the version passed in. It might happen that
             * the heart beat version maybe lesser than the version passed
             * in and some application state has a version that is greater
             * than the version passed in. In this case we also send the old
             * heart beat and throw it away on the receiver if it is redundant.
            */
            int localHbVersion = epState.getHeartBeatState().getHeartBeatVersion();
            if (localHbVersion > version) {
                reqdEndpointState = new EndpointState(epState.getHeartBeatState());
                if (logger.isTraceEnabled())
                    logger.trace("local heartbeat version {} greater than {} for {}", localHbVersion, version,
                            forEndpoint);
            }
            /* Accumulate all application states whose versions are greater than "version" variable */
            for (Entry<ApplicationState, VersionedValue> entry : epState.getApplicationStateMap().entrySet()) {
                VersionedValue value = entry.getValue();
                if (value.version > version) {
                    if (reqdEndpointState == null) {
                        reqdEndpointState = new EndpointState(epState.getHeartBeatState());
                    }
                    final ApplicationState key = entry.getKey();
                    if (logger.isTraceEnabled())
                        logger.trace("Adding state {}: {}", key, value.value);
                    reqdEndpointState.addApplicationState(key, value);
                }
            }
        }
        return reqdEndpointState;
    }

    /**
     * determine which endpoint started up earlier
     */
    public int compareEndpointStartup(NetEndpoint addr1, NetEndpoint addr2) {
        EndpointState ep1 = getEndpointState(addr1);
        EndpointState ep2 = getEndpointState(addr2);
        assert ep1 != null && ep2 != null;
        return ep1.getHeartBeatState().getGeneration() - ep2.getHeartBeatState().getGeneration();
    }

    void notifyFailureDetector(Map<NetEndpoint, EndpointState> remoteEpStateMap) {
        for (Entry<NetEndpoint, EndpointState> entry : remoteEpStateMap.entrySet()) {
            notifyFailureDetector(entry.getKey(), entry.getValue());
        }
    }

    void notifyFailureDetector(NetEndpoint endpoint, EndpointState remoteEndpointState) {
        EndpointState localEndpointState = endpointStateMap.get(endpoint);
        /*
         * If the local endpoint state exists then report to the FD only
         * if the versions workout.
        */
        if (localEndpointState != null) {
            IFailureDetector fd = FailureDetector.instance;
            int localGeneration = localEndpointState.getHeartBeatState().getGeneration();
            int remoteGeneration = remoteEndpointState.getHeartBeatState().getGeneration();
            if (remoteGeneration > localGeneration) {
                localEndpointState.updateTimestamp();
                // this node was dead and the generation changed, this indicates a reboot, or possibly a takeover
                // we will clean the fd intervals for it and relearn them
                if (!localEndpointState.isAlive()) {
                    logger.debug("Clearing interval times for {} due to generation change", endpoint);
                    fd.remove(endpoint);
                }
                fd.report(endpoint);
                return;
            }

            if (remoteGeneration == localGeneration) {
                int localVersion = getMaxEndpointStateVersion(localEndpointState);
                int remoteVersion = remoteEndpointState.getHeartBeatState().getHeartBeatVersion();
                if (remoteVersion > localVersion) {
                    localEndpointState.updateTimestamp();
                    // just a version change, report to the fd
                    fd.report(endpoint);
                }
            }
        }
    }

    private void markAlive(final NetEndpoint addr, final EndpointState localState) {
        localState.markDead();

        MessageOut<EchoMessage> echoMessage = new MessageOut<>(Verb.ECHO, new EchoMessage());
        logger.trace("Sending a EchoMessage to {}", addr);
        IAsyncCallback<Void> echoHandler = new IAsyncCallback<Void>() {
            @Override
            public boolean isLatencyForSnitch() {
                return false;
            }

            @Override
            public void response(MessageIn<Void> msg) {
                realMarkAlive(addr, localState);
            }
        };
        MessagingService.instance().sendRR(echoMessage, addr, echoHandler);
    }

    private void realMarkAlive(final NetEndpoint addr, final EndpointState localState) {
        if (logger.isTraceEnabled())
            logger.trace("marking as alive {}", addr);
        localState.markAlive();
        localState.updateTimestamp(); // prevents doStatusCheck from racing us and evicting if it was down >
                                      // aVeryLongTime
        liveEndpoints.add(addr);
        unreachableEndpoints.remove(addr);
        expireTimeEndpointMap.remove(addr);
        logger.debug("removing expire time for endpoint : {}", addr);
        logger.info("Node {} is now UP", addr);
        for (IEndpointStateChangeSubscriber subscriber : subscribers)
            subscriber.onAlive(addr, localState);
        if (logger.isTraceEnabled())
            logger.trace("Notified {}", subscribers);
    }

    private void markDead(NetEndpoint addr, EndpointState localState) {
        if (logger.isTraceEnabled())
            logger.trace("marking as down {}", addr);
        localState.markDead();
        liveEndpoints.remove(addr);
        unreachableEndpoints.put(addr, System.nanoTime());
        logger.info("Node {} is now DOWN", addr);
        for (IEndpointStateChangeSubscriber subscriber : subscribers)
            subscriber.onDead(addr, localState);
        if (logger.isTraceEnabled())
            logger.trace("Notified {}", subscribers);
    }

    /**
     * This method is called whenever there is a "big" change in ep state (a generation change for a known node).
     *
     * @param ep      endpoint
     * @param epState EndpointState for the endpoint
     */
    private void handleMajorStateChange(NetEndpoint ep, EndpointState epState) {
        if (!isDeadState(epState)) {
            if (endpointStateMap.get(ep) != null)
                logger.info("Node {} has restarted, now UP", ep);
            else
                logger.info("Node {} is now part of the cluster", ep);
        }
        if (logger.isTraceEnabled())
            logger.trace("Adding endpoint state for {}", ep);
        endpointStateMap.put(ep, epState);

        // the node restarted: it is up to the subscriber to take whatever action is necessary
        for (IEndpointStateChangeSubscriber subscriber : subscribers)
            subscriber.onRestart(ep, epState);

        if (!isDeadState(epState))
            markAlive(ep, epState);
        else {
            logger.debug("Not marking {} alive due to dead state", ep);
            markDead(ep, epState);
        }
        for (IEndpointStateChangeSubscriber subscriber : subscribers)
            subscriber.onJoin(ep, epState);
    }

    public boolean isDeadState(EndpointState epState) {
        if (epState.getApplicationState(ApplicationState.STATUS) == null)
            return false;
        String value = epState.getApplicationState(ApplicationState.STATUS).value;
        String[] pieces = value.split(VersionedValue.DELIMITER_STR, -1);
        assert (pieces.length > 0);
        String state = pieces[0];
        for (String deadstate : DEAD_STATES) {
            if (state.equals(deadstate))
                return true;
        }
        return false;
    }

    void applyStateLocally(Map<NetEndpoint, EndpointState> epStateMap) {
        for (Entry<NetEndpoint, EndpointState> entry : epStateMap.entrySet()) {
            NetEndpoint ep = entry.getKey();
            if (ep.equals(ConfigDescriptor.getLocalEndpoint()) && !isInShadowRound())
                continue;
            if (justRemovedEndpoints.containsKey(ep)) {
                if (logger.isTraceEnabled())
                    logger.trace("Ignoring gossip for {} because it is quarantined", ep);
                continue;
            }

            EndpointState localEpStatePtr = endpointStateMap.get(ep);
            EndpointState remoteState = entry.getValue();
            /*
                If state does not exist just add it. If it does then add it if the remote generation is greater.
                If there is a generation tie, attempt to break it by heartbeat version.
            */
            if (localEpStatePtr != null) {
                int localGeneration = localEpStatePtr.getHeartBeatState().getGeneration();
                int remoteGeneration = remoteState.getHeartBeatState().getGeneration();
                if (logger.isTraceEnabled())
                    logger.trace("{} local generation {}, remote generation {}", ep, localGeneration, remoteGeneration);

                if (localGeneration != 0 && remoteGeneration > localGeneration + MAX_GENERATION_DIFFERENCE) {
                    // assume some peer has corrupted memory
                    // and is broadcasting an unbelievable generation about another peer (or itself)
                    logger.warn(
                            "received an invalid gossip generation for peer {}; "
                                    + "local generation = {}, received generation = {}",
                            ep, localGeneration, remoteGeneration);
                } else if (remoteGeneration > localGeneration) {
                    if (logger.isTraceEnabled())
                        logger.trace("Updating heartbeat state generation to {} from {} for {}", remoteGeneration,
                                localGeneration, ep);
                    // major state change will handle the update by inserting the remote state directly
                    handleMajorStateChange(ep, remoteState);
                } else if (remoteGeneration == localGeneration) // generation has not changed, apply new states
                {
                    /* find maximum state */
                    int localMaxVersion = getMaxEndpointStateVersion(localEpStatePtr);
                    int remoteMaxVersion = getMaxEndpointStateVersion(remoteState);
                    if (remoteMaxVersion > localMaxVersion) {
                        // apply states, but do not notify since there is no major change
                        applyNewStates(ep, localEpStatePtr, remoteState);
                    } else if (logger.isTraceEnabled())
                        logger.trace("Ignoring remote version {} <= {} for {}", remoteMaxVersion, localMaxVersion, ep);
                    if (!localEpStatePtr.isAlive() && !isDeadState(localEpStatePtr)) // unless of course, it was dead
                        markAlive(ep, localEpStatePtr);
                } else {
                    if (logger.isTraceEnabled())
                        logger.trace("Ignoring remote generation {} < {}", remoteGeneration, localGeneration);
                }
            } else {
                // this is a new node, report it to the FD in case it is the first time we are seeing it AND it's not
                // alive
                FailureDetector.instance.report(ep);
                handleMajorStateChange(ep, remoteState);
            }
        }
    }

    private void applyNewStates(NetEndpoint addr, EndpointState localState, EndpointState remoteState) {
        // don't assert here, since if the node restarts the version will go back to zero
        int oldVersion = localState.getHeartBeatState().getHeartBeatVersion();

        localState.setHeartBeatState(remoteState.getHeartBeatState());
        if (logger.isTraceEnabled())
            logger.trace("Updating heartbeat state version to {} from {} for {} ...",
                    localState.getHeartBeatState().getHeartBeatVersion(), oldVersion, addr);

        // we need to make two loops here, one to apply, then another to notify,
        // this way all states in an update are present and current when the notifications are received
        for (Entry<ApplicationState, VersionedValue> remoteEntry : remoteState.getApplicationStateMap().entrySet()) {
            ApplicationState remoteKey = remoteEntry.getKey();
            VersionedValue remoteValue = remoteEntry.getValue();

            assert remoteState.getHeartBeatState().getGeneration() == localState.getHeartBeatState().getGeneration();
            localState.addApplicationState(remoteKey, remoteValue);
        }
        for (Entry<ApplicationState, VersionedValue> remoteEntry : remoteState.getApplicationStateMap().entrySet()) {
            doOnChangeNotifications(addr, remoteEntry.getKey(), remoteEntry.getValue());
        }
    }

    // notify that a local application state is going to change (doesn't get triggered for remote changes)
    private void doBeforeChangeNotifications(NetEndpoint addr, EndpointState epState, ApplicationState apState,
            VersionedValue newValue) {
        for (IEndpointStateChangeSubscriber subscriber : subscribers) {
            subscriber.beforeChange(addr, epState, apState, newValue);
        }
    }

    // notify that an application state has changed
    private void doOnChangeNotifications(NetEndpoint addr, ApplicationState state, VersionedValue value) {
        for (IEndpointStateChangeSubscriber subscriber : subscribers) {
            subscriber.onChange(addr, state, value);
        }
    }

    /* Request all the state for the endpoint in the gDigest */
    private void requestAll(GossipDigest gDigest, List<GossipDigest> deltaGossipDigestList, int remoteGeneration) {
        /* We are here since we have no data for this endpoint locally so request everthing. */
        deltaGossipDigestList.add(new GossipDigest(gDigest.getEndpoint(), remoteGeneration, 0));
        if (logger.isTraceEnabled())
            logger.trace("requestAll for {}", gDigest.getEndpoint());
    }

    /* Send all the data with version greater than maxRemoteVersion */
    private void sendAll(GossipDigest gDigest, Map<NetEndpoint, EndpointState> deltaEpStateMap, int maxRemoteVersion) {
        EndpointState localEpStatePtr = getStateForVersionBiggerThan(gDigest.getEndpoint(), maxRemoteVersion);
        if (localEpStatePtr != null)
            deltaEpStateMap.put(gDigest.getEndpoint(), localEpStatePtr);
    }

    /*
        This method is used to figure the state that the Gossiper has but Gossipee doesn't. The delta digests
        and the delta state are built up.
    */
    void examineGossiper(List<GossipDigest> gDigestList, List<GossipDigest> deltaGossipDigestList,
            Map<NetEndpoint, EndpointState> deltaEpStateMap) {
        if (gDigestList.isEmpty()) {
            /* we've been sent a *completely* empty syn, 
             * which should normally never happen since an endpoint will at least send a syn with itself.
             * If this is happening then the node is attempting shadow gossip, and we should reply with everything we know.
             */
            if (logger.isDebugEnabled())
                logger.debug("Shadow request received, adding all states");
            for (Map.Entry<NetEndpoint, EndpointState> entry : endpointStateMap.entrySet()) {
                gDigestList.add(new GossipDigest(entry.getKey(), 0, 0));
            }
        }
        for (GossipDigest gDigest : gDigestList) {
            int remoteGeneration = gDigest.getGeneration();
            int maxRemoteVersion = gDigest.getMaxVersion();
            /* Get state associated with the end point in digest */
            EndpointState epStatePtr = endpointStateMap.get(gDigest.getEndpoint());
            /*
                Here we need to fire a GossipDigestAckMessage. If we have some data associated with this endpoint locally
                then we follow the "if" path of the logic. If we have absolutely nothing for this endpoint we need to
                request all the data for this endpoint.
            */
            if (epStatePtr != null) {
                int localGeneration = epStatePtr.getHeartBeatState().getGeneration();
                /* get the max version of all keys in the state associated with this endpoint */
                int maxLocalVersion = getMaxEndpointStateVersion(epStatePtr);
                if (remoteGeneration == localGeneration && maxRemoteVersion == maxLocalVersion)
                    continue;

                if (remoteGeneration > localGeneration) {
                    /* we request everything from the gossiper */
                    requestAll(gDigest, deltaGossipDigestList, remoteGeneration);
                } else if (remoteGeneration < localGeneration) {
                    /* send all data with generation = localgeneration and version > 0 */
                    sendAll(gDigest, deltaEpStateMap, 0);
                } else if (remoteGeneration == localGeneration) {
                    /*
                        If the max remote version is greater then we request the remote endpoint send us all the data
                        for this endpoint with version greater than the max version number we have locally for this
                        endpoint.
                        If the max remote version is lesser, then we send all the data we have locally for this endpoint
                        with version greater than the max remote version.
                    */
                    if (maxRemoteVersion > maxLocalVersion) {
                        deltaGossipDigestList
                                .add(new GossipDigest(gDigest.getEndpoint(), remoteGeneration, maxLocalVersion));
                    } else if (maxRemoteVersion < maxLocalVersion) {
                        /* send all data with generation = localgeneration and version > maxRemoteVersion */
                        sendAll(gDigest, deltaEpStateMap, maxRemoteVersion);
                    }
                }
            } else {
                /* We are here since we have no data for this endpoint locally so request everything. */
                requestAll(gDigest, deltaGossipDigestList, remoteGeneration);
            }
        }
    }

    /**
     *  Do a single 'shadow' round of gossip, where we do not modify any state
     *  Only used when replacing a node, to get and assume its states
     */
    public void doShadowRound() {
        buildSeedsList();
        // send a completely empty syn
        List<GossipDigest> gDigests = new ArrayList<>();
        GossipDigestSyn digestSynMessage = new GossipDigestSyn(ConfigDescriptor.getClusterName(), gDigests);
        MessageOut<GossipDigestSyn> message = new MessageOut<>(Verb.GOSSIP_DIGEST_SYN, digestSynMessage);
        inShadowRound = true;
        for (NetEndpoint seed : seeds)
            MessagingService.instance().sendOneWay(message, seed);
        int slept = 0;
        try {
            while (true) {
                Thread.sleep(1000);
                if (!inShadowRound)
                    break;
                slept += 1000;
                if (slept > RING_DELAY)
                    throw new RuntimeException("Unable to gossip with any seeds");
            }
        } catch (InterruptedException wtf) {
            throw new RuntimeException(wtf);
        }
    }

    protected void finishShadowRound() {
        if (inShadowRound)
            inShadowRound = false;
    }

    protected boolean isInShadowRound() {
        return inShadowRound;
    }

    /**
     * Add an endpoint we knew about previously, but whose state is unknown
     */
    public void addSavedEndpoint(NetEndpoint ep) {
        if (ep.equals(ConfigDescriptor.getLocalEndpoint())) {
            logger.debug("Attempt to add self as saved endpoint");
            return;
        }

        // preserve any previously known, in-memory data about the endpoint (such as DC, RACK, and so on)
        EndpointState epState = endpointStateMap.get(ep);
        if (epState != null) {
            logger.debug("not replacing a previous epState for {}, but reusing it: {}", ep, epState);
            epState.setHeartBeatState(new HeartBeatState(0));
        } else {
            epState = new EndpointState(new HeartBeatState(0));
        }

        epState.markDead();
        endpointStateMap.put(ep, epState);
        unreachableEndpoints.put(ep, System.nanoTime());
        if (logger.isTraceEnabled())
            logger.trace("Adding saved endpoint {} {}", ep, epState.getHeartBeatState().getGeneration());
    }

    public void addLocalApplicationState(ApplicationState state, VersionedValue value) {
        NetEndpoint epAddr = ConfigDescriptor.getLocalEndpoint();
        EndpointState epState = endpointStateMap.get(epAddr);
        assert epState != null;
        // Fire "before change" notifications:
        doBeforeChangeNotifications(epAddr, epState, state, value);
        // Notifications may have taken some time, so preventively raise the version
        // of the new value, otherwise it could be ignored by the remote node
        // if another value with a newer version was received in the meantime:
        value = P2pServer.valueFactory.cloneWithHigherVersion(value);
        // Add to local application state and fire "on change" notifications:
        epState.addApplicationState(state, value);
        doOnChangeNotifications(epAddr, state, value);
    }

    public void addLocalApplicationStates(List<Pair<ApplicationState, VersionedValue>> states) {
        taskLock.lock();
        try {
            for (Pair<ApplicationState, VersionedValue> pair : states) {
                addLocalApplicationState(pair.left, pair.right);
            }
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        if (scheduledGossipTask != null)
            scheduledGossipTask.cancel(false);
        logger.info("Announcing shutdown");
        Uninterruptibles.sleepUninterruptibly(INTERVAL_IN_MILLIS * 2, TimeUnit.MILLISECONDS);
        MessageOut<?> message = new MessageOut<>(Verb.GOSSIP_SHUTDOWN);
        for (NetEndpoint ep : liveEndpoints)
            MessagingService.instance().sendOneWay(message, ep);
    }

    public boolean isEnabled() {
        return (scheduledGossipTask != null) && (!scheduledGossipTask.isCancelled());
    }

    public void addExpireTimeForEndpoint(NetEndpoint endpoint, long expireTime) {
        if (logger.isDebugEnabled()) {
            logger.debug("adding expire time for endpoint : {} ({})", endpoint, expireTime);
        }
        expireTimeEndpointMap.put(endpoint, expireTime);
    }

    public static long computeExpireTime() {
        return System.currentTimeMillis() + Gossiper.A_VERY_LONG_TIME;
    }

    // @VisibleForTesting
    // public void injectApplicationState(NetEndpoint endpoint, ApplicationState state, VersionedValue value) {
    // EndpointState localState = endpointStateMap.get(endpoint);
    // localState.addApplicationState(state, value);
    // }
    // public boolean seenAnySeed() {
    // for (Map.Entry<NetEndpoint, EndpointState> entry : endpointStateMap.entrySet()) {
    // if (seeds.contains(entry.getKey()))
    // return true;
    // try {
    // if (entry.getValue().getApplicationStateMap().containsKey(ApplicationState.INTERNAL_IP)
    // && seeds.contains(NetEndpoint
    // .getByName(entry.getValue().getApplicationState(ApplicationState.INTERNAL_IP).value)))
    // return true;
    // } catch (UnknownHostException e) {
    // throw new RuntimeException(e);
    // }
    // }
    // return false;
    // }
    //
    // public NetEndpoint getFirstLiveSeedEndpoint() {
    // for (NetEndpoint seed : ConfigDescriptor.getSeedList()) {
    // if (FailureDetector.instance.isAlive(seed))
    // return seed;
    // }
    // throw new IllegalStateException("Unable to find any live seeds!");
    // }
    //
    // public NetEndpoint getLiveSeedEndpoint() {
    // IEndpointSnitch snitch = ConfigDescriptor.getEndpointSnitch();
    // String dc = snitch.getDatacenter(ConfigDescriptor.getLocalEndpoint());
    // for (NetEndpoint seed : ConfigDescriptor.getSeedList()) {
    // if (FailureDetector.instance.isAlive(seed) && dc.equals(snitch.getDatacenter(seed)))
    // return seed;
    // }
    //
    // for (NetEndpoint seed : ConfigDescriptor.getSeedList()) {
    // if (FailureDetector.instance.isAlive(seed))
    // return seed;
    // }
    // throw new IllegalStateException("Unable to find any live seeds!");
    // }
}

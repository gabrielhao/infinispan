/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.infinispan.cacheviews;

import org.infinispan.CacheException;
import org.infinispan.commands.control.CacheViewControlCommand;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.CacheManagerNotifier;
import org.infinispan.notifications.cachemanagerlistener.annotation.Merged;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.MembershipArithmetic;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.infinispan.factories.KnownComponentNames.ASYNC_TRANSPORT_EXECUTOR;

/**
 * CacheViewsManager implementation.
 *
 * It uses {@link org.infinispan.commands.control.CacheViewControlCommand}s to organize the installation of cache views in two phases.
 * There are three phases in installing a cache view:
 * 1. A node wants to start or stop the cache, sending a REQUEST_JOIN or a REQUEST_LEAVE.
 *    A node leaving the JGroups cluster is interpreted as a REQUEST_LEAVE for all its caches.
 *    The request will be broadcast to all the cluster members, as all the nodes need to stop sending requests to the leavers.
 * 2. For join requests, the cache views manager will wait for a short period of time to allow other members to join.
 * 3. The coordinator then sends a PREPARE_VIEW to all the nodes that have the cache started (or starting).
 *    Any node can veto the view by throwing an exception in this phase.
 * 4. The coordinator sends a COMMIT_VIEW to all the nodes that have the cache started.
 * 4.1. If a node threw an exception during PREPARE_VIEW, the coordinator will send a ROLLBACK_VIEW instead.
 *      After a configurable amount of time the coordinator may retry to install the view, but with a different
 *      view id (even if the members are the same).
 *
 * Only the coordinator keeps the information about which nodes have requested to join, so when
 * the coordinator changes the new coordinator will have to request state from all the members using
 * the RECOVER_VIEW command. This also happens after a merge, when the coordinator changes only for some of the nodes.
 *
 * @author Dan Berindei &lt;dan@infinispan.org&gt;
 * @since 5.1
 */
public class CacheViewsManagerImpl implements CacheViewsManager {
   private static final Log log = LogFactory.getLog(CacheViewsManagerImpl.class);
   public static final String DUMMY_CACHE_NAME_FOR_GLOBAL_COMMANDS = "__dummy_cache_name_for_global_commands__";

   //private GlobalComponentRegistry gcr;
   private CacheManagerNotifier cacheManagerNotifier;
   private Transport transport;

   private volatile boolean running = false;
   private volatile List<Address> members;
   private volatile Address self;
   private volatile Address coordinator;
   private volatile boolean isCoordinator;
   private volatile boolean shouldRecoverViews;

   // the complete state of every cache in the cluster
   // entries can only be added, never removed
   private final ConcurrentMap<String, CacheViewInfo> viewsInfo = new ConcurrentHashMap<String, CacheViewInfo>();
   // only used if this node is the coordinator
   private long timeout = 10 * 1000;
   // TODO Make the cooldown configurable, or change the view installation timing altogether
   private long viewChangeCooldown = 1 * 1000;
   private ViewListener listener = new ViewListener();;

   // A single thread examines the unprepared changes and decides whether to install a new view for all the caches
   private ViewTriggerThread viewTriggerThread;
   private ExecutorService cacheViewInstallerExecutor;
   private ExecutorService asyncTransportExecutor;

   public CacheViewsManagerImpl() {
   }

   @Inject
   public void init(CacheManagerNotifier cacheManagerNotifier, Transport transport,
                    @ComponentName(ASYNC_TRANSPORT_EXECUTOR) ExecutorService e,
                    GlobalConfiguration globalConfiguration) {
      this.cacheManagerNotifier = cacheManagerNotifier;
      this.transport = transport;
      this.asyncTransportExecutor = e;
      // TODO Try to implement a "total view installation time budget" instead of the current per-operation timeout
      this.timeout = globalConfiguration.getDistributedSyncTimeout();
   }

   // Start after JGroupsTransport so that we have a view already
   @Start(priority = 11)
   public void start() throws Exception {
      if (transport == null)
         return;

      self = transport.getAddress();
      running = true;

      // TODO make the cache view installer executor configurable
      ThreadFactory tfViewInstaller = new ThreadFactory() {
         private volatile AtomicInteger count = new AtomicInteger(0);

         @Override
         public Thread newThread(Runnable r) {
            return new Thread(r, "CacheViewInstaller-" + count.incrementAndGet() + "," + self);
         }
      };
      cacheViewInstallerExecutor = Executors.newCachedThreadPool(tfViewInstaller);

      viewTriggerThread = new ViewTriggerThread();

      cacheManagerNotifier.addListener(listener);
      // The listener already missed the initial view
      handleNewView(transport.getMembers(), false, true);

      // TODO Request an initial view of all the caches in the cluster and maintain that view
      // so that a node can use the cache without ever joining and starting to hold data.
   }

   @Stop(priority = 0)
   public void stop() {
      cacheManagerNotifier.removeListener(listener);
      running = false;
      viewTriggerThread.interrupt();
      cacheViewInstallerExecutor.shutdown();
      try {
         viewTriggerThread.join(timeout);
         cacheViewInstallerExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
      }
   }

   @Override
   public CacheView getCommittedView(String cacheName) {
      return viewsInfo.get(cacheName).getCommittedView();
   }

   @Override
   public CacheView getPendingView(String cacheName) {
      return viewsInfo.get(cacheName).getPendingView();
   }

   @Override
   public Set<Address> getLeavers(String cacheName) {
      return viewsInfo.get(cacheName).getPendingChanges().getLeavers();
   }

   @Override
   public void join(String cacheName, CacheMembershipListener listener) throws Exception {
      // first keep track of the join locally
      handleRequestJoin(self, cacheName);
      viewsInfo.get(cacheName).setListener(listener);

      // then ask the coordinator to join and use its existing cache view
      if (!isCoordinator) {
         final CacheViewControlCommand cmd = new CacheViewControlCommand(cacheName,
               CacheViewControlCommand.Type.REQUEST_JOIN, self);
         // If we get a SuspectException we can ignore it, the new coordinator will come asking for our state anyway
         Map<Address,Response> rspList = transport.invokeRemotely(Collections.singleton(coordinator), cmd,
               ResponseMode.SYNCHRONOUS, timeout, false, null, false);
         checkRemoteResponse(cacheName, cmd, rspList);
      }
   }

   @Override
   public void leave(String cacheName) {
      try {
         // remove the local listener
         viewsInfo.get(cacheName).setListener(null);

         // update the local cache state
         handleRequestLeave(self, cacheName);

         // finally broadcast the leave request to all the members
         final CacheViewControlCommand cmd = new CacheViewControlCommand(cacheName,
               CacheViewControlCommand.Type.REQUEST_LEAVE, self);
         // ignore any response from the other members
         transport.invokeRemotely(members, cmd, ResponseMode.ASYNCHRONOUS, timeout, false, null, false);
      } catch (Exception e) {
         log.debugf(e, "%s: Error while leaving cache view", cacheName);
      }
   }


   /**
    * Called on the coordinator to install a new view in the cluster.
    * It follows the protocol in the class description.
    */
   boolean clusterInstallView(String cacheName, CacheView newView) throws Exception {
      boolean success = false;
      try {
         clusterPrepareView(cacheName, newView);
         success = true;
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      } finally {
         // Cache manager is shutting down, don't try to commit or roll back
         if (!isRunning())
            return false;

         if (success) {
            clusterCommitView(cacheName, newView.getViewId(), newView.getMembers(), true);
         } else {
            int committedViewId = viewsInfo.get(cacheName).getCommittedView().getViewId();
            clusterRollbackView(cacheName, committedViewId, newView.getMembers(), true);
         }
      }
      return success;
   }

   /**
    * The prepare phase of view installation.
    */
   private CacheView clusterPrepareView(final String cacheName, final CacheView pendingView) throws Exception {
      final CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      final CacheView committedView = cacheViewInfo.getCommittedView();

      final CacheViewControlCommand cmd = new CacheViewControlCommand(cacheName,
            CacheViewControlCommand.Type.PREPARE_VIEW, self, pendingView.getViewId(),
            pendingView.getMembers(), committedView.getViewId(), committedView.getMembers());

      final List<Address> targets = pendingView.getMembers();
      targets.removeAll(cacheViewInfo.getPendingChanges().getLeavers());

      // broadcast the command to the targets, which will skip the local node
      Future<Map<Address, Response>> future = asyncTransportExecutor.submit(new Callable<Map<Address, Response>>() {
         @Override
         public Map<Address, Response> call() throws Exception {
            Map<Address, Response> rspList = transport.invokeRemotely(targets, cmd, ResponseMode.SYNCHRONOUS, timeout, false, null, false);
            return rspList;
         }
      });

      // now invoke the command on the local node
      handlePrepareView(cacheName, pendingView, committedView);

      // wait for the remote commands to finish
      Map<Address, Response> rspList = future.get(timeout, TimeUnit.MILLISECONDS);
      checkRemoteResponse(cacheName, cmd, rspList);
      return pendingView;
   }

   /**
    * The rollback phase of view installation.
    */
   private void clusterRollbackView(final String cacheName, int committedViewId, List<Address> targets, boolean includeCoordinator) {
      final CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      final int newViewId = cacheViewInfo.getPendingChanges().getRollbackViewId();
      final List<Address> validTargets = new ArrayList<Address>(targets);
      validTargets.removeAll(cacheViewInfo.getPendingChanges().getLeavers());
      log.tracef("%s: Rolling back to cache view %d on %s, new view id is %d", cacheName, committedViewId, validTargets, newViewId);

      try {
         // it's ok to send the rollback to nodes that don't have the cache yet, they will just ignore it
         // on the other hand we *have* to send the rollback to any nodes that got the prepare
         final CacheViewControlCommand cmd = new CacheViewControlCommand(cacheName,
               CacheViewControlCommand.Type.ROLLBACK_VIEW, self, newViewId, null, committedViewId, null);
         // wait until we get all the responses, but ignore the results
         Map<Address, Response> rspList = transport.invokeRemotely(validTargets, cmd, ResponseMode.SYNCHRONOUS, timeout, false, null, false);
         checkRemoteResponse(cacheName, cmd, rspList);
      } catch (Exception e) {
         log.cacheViewRollbackFailure(e, cacheName, committedViewId);
      }

      // in the end we roll back locally, so any pending changes can trigger a new view installation
      if (includeCoordinator || validTargets.contains(self)) {
         handleRollbackView(cacheName, newViewId, committedViewId);
      }
   }

   /**
    * The commit phase of view installation.
    */
   private void clusterCommitView(final String cacheName, final int viewId, List<Address> targets, boolean includeCoordinator) {
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      final List<Address> validTargets = new ArrayList<Address>(targets);
      // TODO Retry the commit if one of the targets left the cluster (even with this precaution)
      validTargets.removeAll(cacheViewInfo.getPendingChanges().getLeavers());
      log.tracef("%s: Committing cache view %d on %s", cacheName, viewId, targets);

      try {
         // broadcast the command to all the members
         final CacheViewControlCommand cmd = new CacheViewControlCommand(cacheName,
               CacheViewControlCommand.Type.COMMIT_VIEW, self, viewId);
         // wait until we get all the responses, but ignore the results
         Map<Address, Response> rspList = transport.invokeRemotely(validTargets, cmd, ResponseMode.SYNCHRONOUS, timeout, false, null, false);
         checkRemoteResponse(cacheName, cmd, rspList);
      } catch (Exception e) {
         log.cacheViewCommitFailure(e, cacheName, viewId);
      }

      // in the end we commit locally, so any pending changes can trigger a new view installation
      if (includeCoordinator || validTargets.contains(self)) {
         handleCommitView(cacheName, viewId);
      }
   }


   /**
    * Handle a join request.
    * Even if this node is not the coordinator this method will still be called for local caches.
    */
   @Override
   public void handleRequestJoin(Address sender, String cacheName) {
      log.debugf("%s: Node %s is joining", cacheName, sender);

      CacheViewInfo cacheViewInfo = getCacheViewInfo(cacheName);

      // When the coordinator changes there are two possibilities:
      // * either we realize we're the new coordinator first and the join request comes afterwards,
      //   in which case it will trigger a view installation
      // * or the joiner sees us as the coordinator first and we add a join request to the pending changes list
      //   even though we are not the coordinator, and then recoverViews() will trigger the view installation
      // If we die the joiner will get a RECOVER_VIEW command from the new coordinator
      // so the join request will not be lost.
      cacheViewInfo.getPendingChanges().requestJoin(sender);
      viewTriggerThread.wakeUp();
   }

   /**
    * Get the {@code CacheViewInfo} for a cache, or create it with an empty view if it doesn't exist yet.
    */
   private CacheViewInfo getCacheViewInfo(String cacheName) {
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      if (cacheViewInfo == null) {
         // this is the first node to join the cache, create an empty initial view
         cacheViewInfo = new CacheViewInfo(cacheName, CacheView.EMPTY_CACHE_VIEW);
         CacheViewInfo oldInfo = viewsInfo.putIfAbsent(cacheName, cacheViewInfo);
         // if there was an entry already, use that; otherwise use our entry
         if (oldInfo != null) {
            cacheViewInfo = oldInfo;
         }
      }
      return cacheViewInfo;
   }

   @Override
   public void handleRequestLeave(Address sender, String cacheName) {
      handleLeavers(Collections.singleton(sender), cacheName);
      viewTriggerThread.wakeUp();
   }

   private void handleLeavers(Collection<Address> leavers, String cacheName) {
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      if (cacheViewInfo == null)
         return;

      // update the list of leavers - this is only relevant on the coordinator
      if (isCoordinator) {
         cacheViewInfo.getPendingChanges().requestLeave(leavers);
      }

      // tell the upper layer to stop sending commands to the nodes that already left
      if (cacheViewInfo.getListener() != null) {
         if (cacheViewInfo.getCommittedView().getMembers().contains(self)) {
            cacheViewInfo.getListener().updateLeavers(cacheViewInfo.getPendingChanges().getLeavers());
         }
      }
   }

   @Override
   public void handlePrepareView(String cacheName, CacheView pendingView, CacheView committedView) throws Exception {
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      if (cacheViewInfo == null) {
         throw new IllegalStateException(String.format("Received prepare request for cache %s, which is not running", cacheName));
      }

      CacheView lastCommittedView = cacheViewInfo.getCommittedView();

      boolean isLocal = pendingView.getMembers().contains(self);
      if (isLocal || isCoordinator) {
         // The first time we get a PREPARE_VIEW our committed view id is -1, we need to accept any view
         if (lastCommittedView.getViewId() > 0 && lastCommittedView.getViewId() != committedView.getViewId()) {
            log.infof("Our last committed view (%s) is not the same as the coordinator's last committed view (%s). This is normal during a merge",
                  lastCommittedView, committedView);
         }
         cacheViewInfo.prepareView(pendingView);
      }
      if (isLocal && cacheViewInfo.getListener() != null) {
         cacheViewInfo.getListener().prepareView(pendingView, lastCommittedView);
      }
      // any exception here will be propagated back to the coordinator, which will roll back the view installation
   }

   @Override
   public void handleCommitView(String cacheName, int viewId) {
      // on the coordinator: update the committed view and reset the view changes
      // on a cache member: call the listener and update the committed view
      // on a non-member: do nothing
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      if (cacheViewInfo == null) {
         log.tracef("Ignoring view commit for unknown cache %s", cacheName);
         return;
      }

      boolean isLocal = cacheViewInfo.getPendingView() != null && cacheViewInfo.getPendingView().getMembers().contains(self);
      if (isLocal || isCoordinator) {
         log.debugf("%s: Committing cache view %d", cacheName, viewId);
         cacheViewInfo.commitView(viewId);
         cacheViewInfo.getPendingChanges().resetChanges(cacheViewInfo.getCommittedView());
         if (isLocal && cacheViewInfo.getListener() != null) {
            cacheViewInfo.getListener().commitView(viewId);
         }
      } else {
         log.debugf("%s: We don't have a pending view, ignoring commit", cacheName);
      }
   }

   @Override
   public void handleRollbackView(String cacheName, int newViewId, int committedViewId) {
      CacheViewInfo cacheViewInfo = viewsInfo.get(cacheName);
      if (cacheViewInfo == null) {
         log.tracef("Ignoring view rollback for unknown cache %s", cacheName);
         return;
      }

      boolean isLocal = cacheViewInfo.getCommittedView().getMembers().contains(self);
      if (isLocal || isCoordinator) {
         log.debugf("%s: Rolling back to cache view %d, new view id is %d", cacheName, committedViewId, newViewId);
         cacheViewInfo.rollbackView(newViewId, committedViewId);
         cacheViewInfo.getPendingChanges().resetChanges(cacheViewInfo.getCommittedView());
         if (isLocal && cacheViewInfo.getListener() != null) {
            cacheViewInfo.getListener().rollbackView(committedViewId);
         }
      } else {
         log.debugf("%s: We don't have a pending view, ignoring rollback", cacheName);
      }
   }

   @Override
   public Map<String, CacheView> handleRecoverViews() {
      Map<String, CacheView> result = new HashMap<String, CacheView>();
      for (CacheViewInfo cacheViewInfo : viewsInfo.values()) {
         if (cacheViewInfo.getCommittedView().getMembers().contains(self)) {
            result.put(cacheViewInfo.getCacheName(), cacheViewInfo.getCommittedView());
         }
      }
      return result;
   }

   private void handleNewView(List<Address> newMembers, boolean mergeView, boolean initialView) {
      boolean wasCoordinator = isCoordinator;
      coordinator = transport.getCoordinator();
      isCoordinator = transport.isCoordinator();


      if (isCoordinator && (mergeView || !wasCoordinator && !initialView)) {
         shouldRecoverViews = true;
         log.tracef("Node %s has become the coordinator", self);
      }

      // The view trigger thread might have just passed the recovery check, so we set the members last
      // to ensure that it doesn't start processing leavers before doing the recovery
      members = newMembers;

      viewTriggerThread.wakeUp();
   }

   /**
    * Check the results of a remote command and throw an exception if any of them is unsuccessful.
    */
   private void checkRemoteResponse(String cacheName, CacheViewControlCommand cmd, Map<Address, Response> rspList) {
      boolean success = true;
      for (Map.Entry<Address, Response> response : rspList.entrySet()) {
         // ignore null responses
         if (response.getValue() == null)
            continue;

         if (!response.getValue().isSuccessful()) {
            success = false;
            log.debugf("%s: Received unsuccessful response from node %s: %s", cacheName, response.getKey(), response.getValue());
         }
      }
      if (!success) {
         throw new CacheException(String.format("Error executing command %s remotely", cmd));
      }
   }

   /**
    * Algorithm:
    * 1. The new coordinator sends RECOVER_VIEW to everyone
    * 2. Each node returns a map of started caches -> last committed view
    * 3. For each cache:
    * 3.1. Sort the nodes by their last committed view id, in descending order
    * 3.2. For each node N:
    * 3.2.1. Remove the set NN of all the other nodes M in the last committed view of N from the node list
    * 3.2.2. For each node M in NN:
    * 3.2.2.1. Skip node M if it we didn't get a recovery response from it
    * 3.2.2.2. If node M's last committed view id < N's last committed view id, send a COMMIT_VIEW(N's last committed view id)
    *          // A node couldn't have received the commit command if all the others didn't prepare successfully
    * 3.2.2.3. Else send a ROLLBACK_VIEW(N's last committed view id) to go back to N's last committed view id
    * 3.3. If we had more than one iteration it means we had a merge, so we'll need to install a merged view
    * 3.4. Otherwise we'll rely on the joiners/leavers to trigger a new view
    */
   private void recoverViews() {
      // read the recovery info from every node
      final Map<Address, Map<String, CacheView>> recoveryInfo;
      try {
         log.debugf("Node %s is the new coordinator, recovering cache views", self);

         recoveryInfo = new HashMap<Address, Map<String, CacheView>>();
         // first get the local views
         recoveryInfo.put(self, handleRecoverViews());

         // then broadcast the recover command to all the members
         CacheViewControlCommand cmd = new CacheViewControlCommand(
               DUMMY_CACHE_NAME_FOR_GLOBAL_COMMANDS, CacheViewControlCommand.Type.RECOVER_VIEW, self);
         Map<Address, Response> rspList = transport.invokeRemotely(null, cmd,
               ResponseMode.SYNCHRONOUS, timeout, true, null, false);
         checkRemoteResponse(null, cmd, rspList);
         for (Map.Entry<Address, Response> e : rspList.entrySet()) {
            SuccessfulResponse value = (SuccessfulResponse) e.getValue();
            recoveryInfo.put(e.getKey(), (Map<String, CacheView>) value.getResponseValue());
         }

         // get the list of caches
         Set<String> cacheNames = new HashSet<String>();
         for (Map<String, CacheView> m : recoveryInfo.values()) {
            cacheNames.addAll(m.keySet());
         }

         // now apply the algorithm for each cache
         for (final String cacheName : cacheNames) {
            // get the list of nodes for this cache
            List<Address> recoveredMembers = new ArrayList(recoveryInfo.size());
            for (Map.Entry<Address, Map<String, CacheView>> e : recoveryInfo.entrySet()) {
               // This will include nodes that requested to join but were not committed yet
               if (e.getValue().containsKey(cacheName)) {
                  recoveredMembers.add(e.getKey());
               }
            }

            // sort the collection by the viewId of the current cache
            Collections.sort(recoveredMembers, new Comparator<Address>() {
               @Override
               public int compare(Address o1, Address o2) {
                  return recoveryInfo.get(o2).get(cacheName).getViewId() -
                        recoveryInfo.get(o1).get(cacheName).getViewId();
               }
            });
            log.tracef("%s: Recovered members (including joiners) are %s", cacheName, recoveredMembers);

            CacheViewInfo cacheViewInfo = getCacheViewInfo(cacheName);
            if (recoveredMembers.size() > 0) {
               // the first partition will have the highest view id, so update our latest view id to match that
               int highestViewId = recoveryInfo.get(recoveredMembers.get(0)).get(cacheName).getViewId();
               // there may have been a prepare going on in this partition, make sure our id is greater than that by adding 1
               cacheViewInfo.getPendingChanges().updateLatestViewId(highestViewId + 1);
            }

            // iterate on the nodes, taking all the nodes in a view as a partition
            int partitionCount = 0;
            List<Address> membersToProcess = new LinkedList<Address>(recoveredMembers);
            List<Address> committedMembers = new ArrayList<Address>(recoveredMembers.size());
            while (!membersToProcess.isEmpty()) {
               Address node = membersToProcess.get(0);
               CacheView committedView = recoveryInfo.get(node).get(cacheName);

               final List<Address> partitionMembers = new ArrayList<Address>(committedView.getMembers());
               // exclude from this group nodes that didn't send recovery info
               // or that were included in previous groups
               partitionMembers.retainAll(membersToProcess);
               membersToProcess.removeAll(committedView.getMembers());

               // all the partition members could have left in the meantime, skip to the next partition
               if (partitionMembers.isEmpty())
                  continue;

               // now we have two situations:
               // * either the nodes in the partition have the same view id and we need to roll back
               // * or the nodes have different view ids and we need to commit
               // TODO Is it possible to receive a COMMIT_VIEW from the old coordinator now, after it left the cluster?
               int minViewId = Integer.MAX_VALUE, maxViewId = Integer.MIN_VALUE;
               for (Address partitionMember : partitionMembers) {
                  CacheView pmCommittedView = recoveryInfo.get(partitionMember).get(cacheName);
                  int pmViewId = pmCommittedView.getViewId();
                  if (pmViewId < minViewId)
                     minViewId = pmViewId;
                  if (maxViewId < pmViewId)
                     maxViewId = pmViewId;
               }
               if (minViewId != maxViewId) {
                  log.debugf("Found partition %d (%s) that should have committed view id %d but not all of them do (min view id %d), " +
                        "committing the view", partitionCount, partitionMembers, maxViewId, minViewId);
                  clusterCommitView(cacheName, maxViewId, partitionMembers, false);
               } else {
                  log.debugf("Found partition %d (%s) that has committed view id %d, sending a rollback command " +
                        "to clear any pending prepare", partitionCount, partitionMembers, maxViewId);
                  clusterRollbackView(cacheName, maxViewId, partitionMembers, false);
               }

               committedMembers.addAll(partitionMembers);
               partitionCount++;
            }

            List<Address> joiners = new ArrayList<Address>(recoveredMembers);
            joiners.removeAll(committedMembers);

            // we install a new view even if the member list of this cache didn't change, just to make sure
            cacheViewInfo.getPendingChanges().requestCoordChange(committedMembers, joiners);
         }
      } catch (Exception e) {
         log.error("Error recovering views from the cluster members", e);
         return;
      }
   }

   public boolean isRunning() {
      return running;
   }

   /**
    * Executed on the coordinator to trigger the installation of new views.
    */
   public class ViewTriggerThread extends Thread {
      private Lock lock = new ReentrantLock();
      private Condition condition = lock.newCondition();

      public ViewTriggerThread() {
         super("CacheViewTrigger," + self);
         setDaemon(true);
         start();
      }

      public void wakeUp() {
         lock.lock();
         try {
            log.tracef("Waking up cache view installer thread");
            condition.signal();
         } finally {
            lock.unlock();
         }
      }

      @Override
      public void run() {
         outer: while (isRunning()) {
            if (shouldRecoverViews) {
               recoverViews();
               shouldRecoverViews = false;
            }

            lock.lock();
            try {
               // Ensure at least viewChangeCooldown between cache view changes
               condition.await(viewChangeCooldown, TimeUnit.MILLISECONDS);
               log.tracef("Woke up, shouldRecoverViews=%s", shouldRecoverViews);
            } catch (InterruptedException e) {
               // shutting down
               break;
            } finally {
               lock.unlock();
            }

            if (isCoordinator && isRunning()) {
               // add leave requests for all the leavers x all the caches
               for (CacheViewInfo cacheViewInfo : viewsInfo.values()) {
                  // need to let the listener know about leavers first
                  List<Address> leavers = MembershipArithmetic.getMembersLeft(cacheViewInfo.getCommittedView().getMembers(),
                        members);
                  if (!leavers.isEmpty()) {
                     handleLeavers(leavers, cacheViewInfo.getCacheName());
                  }

                  // check if we are shutting down
                  if (!isRunning())
                     return;
                  // we may have to recover the views before doing anything else
                  if (shouldRecoverViews) {
                     continue outer;
                  }

                  try {
                     PendingCacheViewChanges pendingChanges = cacheViewInfo.getPendingChanges();
                     CacheView pendingView = pendingChanges.createPendingView(cacheViewInfo.getCommittedView());
                     if (pendingView != null) {
                        cacheViewInstallerExecutor.submit(new ViewInstallationTask(cacheViewInfo.getCacheName(), pendingView));
                     }
                  } catch (RuntimeException e) {
                     log.errorTriggeringViewInstallation(e, cacheViewInfo.getCacheName());
                  }
               }
            }
         }
      }

   }

   /**
    * Executed on the coordinator to install a new view in the cluster.
    */
   public class ViewInstallationTask implements Callable<Object> {
      private final String cacheName;
      private final CacheView newView;

      public ViewInstallationTask(String cacheName, CacheView newView) {

         this.cacheName = cacheName;
         this.newView = newView;
      }

      @Override
      public Object call() throws Exception {
         try {
            clusterInstallView(cacheName, newView);
         } catch (Exception e) {
            log.viewInstallationFailure(e, cacheName);
         }
         return null;
      }
   }


   @Listener
   public class ViewListener {
      @Merged
      @ViewChanged
      public void handleViewChange(final ViewChangedEvent e) {
         handleNewView(e.getNewMembers(), e.isMergeView(), e.getViewId() == 0);
      }
   }
}
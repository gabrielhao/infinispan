package org.infinispan.transaction.xa;

import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.equivalence.Equivalence;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.versioning.NumericVersion;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.LocalTransaction;
import org.infinispan.transaction.RemoteTransaction;
import org.infinispan.transaction.synchronization.SyncLocalTransaction;
import org.infinispan.transaction.xa.recovery.RecoveryAwareDldGlobalTransaction;
import org.infinispan.transaction.xa.recovery.RecoveryAwareGlobalTransaction;
import org.infinispan.transaction.xa.recovery.RecoveryAwareLocalTransaction;
import org.infinispan.transaction.xa.recovery.RecoveryAwareRemoteTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.Transaction;
import java.util.Random;

/**
 * Factory for transaction related sate.
 *
 * @author Mircea.Markus@jboss.com
 */
public class TransactionFactory {

   private static final Log log = LogFactory.getLog(TransactionFactory.class);

   private TxFactoryEnum txFactoryEnum;

   private Configuration configuration;
   private VersionGenerator clusterIdGenerator;
   private boolean isClustered;
   private Equivalence<Object> keyEquivalence;

   public enum TxFactoryEnum {

      DLD_RECOVERY_XA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
               Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareLocalTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            RecoveryAwareDldGlobalTransaction dldGlobalTransaction = new RecoveryAwareDldGlobalTransaction(addr, remote);
            // TODO: Not ideal... but causes no issues so far. Could the internal id be an Object instead of a long?
            dldGlobalTransaction.setInternalId(((NumericVersion) clusterIdGenerator.generateNew()).getVersion());
            return addCoinToss(dldGlobalTransaction);
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new RecoveryAwareDldGlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareRemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareRemoteTransaction(tx, topologyId, keyEquivalence);
         }
      },

      DLD_NORECOVERY_XA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
               Equivalence<Object> keyEquivalence) {
            return new LocalXaTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            return addCoinToss(new DldGlobalTransaction(addr, remote));
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new DldGlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(tx, topologyId, keyEquivalence);
         }
      },

      DLD_NORECOVERY_NOXA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
               Equivalence<Object> keyEquivalence) {
            return new SyncLocalTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            return addCoinToss(new DldGlobalTransaction(addr, remote));
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new DldGlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(tx, topologyId, keyEquivalence);
         }
      },
      NODLD_RECOVERY_XA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareLocalTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            RecoveryAwareGlobalTransaction recoveryAwareGlobalTransaction = new RecoveryAwareGlobalTransaction(addr, remote);
            // TODO: Not ideal... but causes no issues so far. Could the internal id be an Object instead of a long?
            recoveryAwareGlobalTransaction.setInternalId(((NumericVersion) clusterIdGenerator.generateNew()).getVersion());
            return recoveryAwareGlobalTransaction;
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new RecoveryAwareGlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareRemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RecoveryAwareRemoteTransaction(tx, topologyId, keyEquivalence);
         }
      },
      NODLD_NORECOVERY_XA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
               Equivalence<Object> keyEquivalence) {
            return new LocalXaTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            return new GlobalTransaction(addr, remote);
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new GlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(tx, topologyId, keyEquivalence);
         }
      },
      NODLD_NORECOVERY_NOXA {
         @Override
         public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
               Equivalence<Object> keyEquivalence) {
            return new SyncLocalTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
         }

         @Override
         public GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered) {
            return new GlobalTransaction(addr, remote);
         }

         @Override
         public GlobalTransaction newGlobalTransaction() {
            return new GlobalTransaction();
         }

         @Override
         public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(modifications, tx, topologyId, keyEquivalence);
         }

         @Override
         public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence) {
            return new RemoteTransaction(tx, topologyId, keyEquivalence);
         }
      };


      public abstract LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId,
            Equivalence<Object> keyEquivalence);
      public abstract GlobalTransaction newGlobalTransaction(Address addr, boolean remote, VersionGenerator clusterIdGenerator, boolean clustered);
      public abstract GlobalTransaction newGlobalTransaction();

      protected long generateRandomId() {
         return rnd.nextLong();
      }

      protected GlobalTransaction addCoinToss(DldGlobalTransaction dldGlobalTransaction) {
         dldGlobalTransaction.setCoinToss(generateRandomId());
         return dldGlobalTransaction;
      }

      /**
       * this class is internally synchronized, so it can be shared between instances
       */
      private final Random rnd = new Random();

      public abstract RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence);

      public abstract RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId, Equivalence<Object> keyEquivalence);
   }


   public GlobalTransaction newGlobalTransaction() {
      return txFactoryEnum.newGlobalTransaction();
   }

   public GlobalTransaction newGlobalTransaction(Address addr, boolean remote) {
      return txFactoryEnum.newGlobalTransaction(addr, remote, this.clusterIdGenerator, isClustered);
   }

   public LocalTransaction newLocalTransaction(Transaction tx, GlobalTransaction gtx, boolean implicitTransaction, int topologyId) {
      return txFactoryEnum.newLocalTransaction(tx, gtx, implicitTransaction, topologyId, keyEquivalence);
   }

   public RemoteTransaction newRemoteTransaction(WriteCommand[] modifications, GlobalTransaction tx, int topologyId) {
      return txFactoryEnum.newRemoteTransaction(modifications, tx, topologyId, keyEquivalence);
   }

   public RemoteTransaction newRemoteTransaction(GlobalTransaction tx, int topologyId) {
      return txFactoryEnum.newRemoteTransaction(tx, topologyId, keyEquivalence);
   }

   @Inject
   public void init(Configuration configuration, VersionGenerator clusterIdGenerator) {
      this.configuration = configuration;
      this.clusterIdGenerator = clusterIdGenerator;
   }

   @Start
   public void start() {
      boolean dldEnabled = configuration.deadlockDetection().enabled();
      boolean xa = !configuration.transaction().useSynchronization();
      boolean recoveryEnabled = configuration.transaction().recovery().enabled();
      boolean batchingEnabled = configuration.invocationBatching().enabled();
      init(dldEnabled, recoveryEnabled, xa, batchingEnabled);
      isClustered = configuration.clustering().cacheMode().isClustered();
      keyEquivalence = configuration.dataContainer().keyEquivalence();
   }

   public void init(boolean dldEnabled, boolean recoveryEnabled, boolean xa, boolean batchingEnabled) {
      if (batchingEnabled) {
         if (dldEnabled) {
            txFactoryEnum = TxFactoryEnum.DLD_NORECOVERY_NOXA;
         } else {
            txFactoryEnum = TxFactoryEnum.NODLD_NORECOVERY_NOXA;
         }
      } else {
         if (dldEnabled) {
            if (recoveryEnabled) {
               if (xa) {
                  txFactoryEnum = TxFactoryEnum.DLD_RECOVERY_XA;
               } else { //using synchronisation enlistment
                  txFactoryEnum = TxFactoryEnum.DLD_NORECOVERY_NOXA;
               }
            } else {
               if (xa) {
                  txFactoryEnum = TxFactoryEnum.DLD_NORECOVERY_XA;
               } else {
                  txFactoryEnum = TxFactoryEnum.DLD_NORECOVERY_NOXA;
               }
            }
         } else {
            if (recoveryEnabled) {
               if (xa) {
                  txFactoryEnum = TxFactoryEnum.NODLD_RECOVERY_XA;
               } else { //using synchronisation enlistment
                  txFactoryEnum = TxFactoryEnum.NODLD_NORECOVERY_NOXA;
               }
            } else {
               if (xa) {
                  txFactoryEnum = TxFactoryEnum.NODLD_NORECOVERY_XA;
               } else {
                  txFactoryEnum = TxFactoryEnum.NODLD_NORECOVERY_NOXA;
               }
            }
         }
      }
      log.tracef("Setting factory enum to %s", txFactoryEnum);
   }
}
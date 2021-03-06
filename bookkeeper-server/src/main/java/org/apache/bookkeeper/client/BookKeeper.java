/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.client.AsyncCallback.CreateCallback;
import org.apache.bookkeeper.client.AsyncCallback.DeleteCallback;
import org.apache.bookkeeper.client.AsyncCallback.OpenCallback;
import org.apache.bookkeeper.client.BKException.Code;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.meta.LedgerManager;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.bookkeeper.proto.BookieClient;
import org.apache.bookkeeper.stats.BookkeeperClientStatsLogger;
import org.apache.bookkeeper.stats.ClientStatsProvider;
import org.apache.bookkeeper.util.BookKeeperSharedSemaphore;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.bookkeeper.zookeeper.ZooKeeperWatcherBase;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BookKeeper client. We assume there is one single writer to a ledger at any
 * time.
 *
 * There are four possible operations: start a new ledger, write to a ledger,
 * read from a ledger and delete a ledger.
 *
 * The exceptions resulting from synchronous calls and error code resulting from
 * asynchronous calls can be found in the class {@link BKException}.
 *
 *
 */

public class BookKeeper {

    static final Logger LOG = LoggerFactory.getLogger(BookKeeper.class);

    final ZooKeeper zk;
    final CountDownLatch connectLatch = new CountDownLatch(1);
    final static int zkConnectTimeoutMs = 5000;
    final ClientSocketChannelFactory channelFactory;

    // The stats logger for this client.
    private final BookkeeperClientStatsLogger statsLogger = ClientStatsProvider.getStatsLoggerInstance();

    // whether the socket factory is one we created, or is owned by whoever
    // instantiated us
    boolean ownChannelFactory = false;
    // whether the zk handle is one we created, or is owned by whoever
    // instantiated us
    boolean ownZKHandle = false;

    final BookieClient bookieClient;
    final BookieWatcher bookieWatcher;

    final OrderedSafeExecutor mainWorkerPool;

    // Ledger manager responsible for how to store ledger meta data
    final LedgerManagerFactory ledgerManagerFactory;
    final LedgerManager ledgerManager;

    final ClientConfiguration conf;

    interface ZKConnectCallback {
        public void connected();
        public void connectionFailed(int code);
    }

    /**
     * Create a bookkeeper client. A zookeeper client and a client socket factory
     * will be instantiated as part of this constructor.
     *
     * @param servers
     *          A list of one of more servers on which zookeeper is running. The
     *          client assumes that the running bookies have been registered with
     *          zookeeper under the path
     *          {@link BookieWatcher#bookieRegistrationPath}
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public BookKeeper(String servers) throws IOException, InterruptedException,
        KeeperException {
        this(new ClientConfiguration().setZkServers(servers));
    }

    /**
     * Create a bookkeeper client using a configuration object.
     * A zookeeper client and a client socket factory will be
     * instantiated as part of this constructor.
     *
     * @param conf
     *          Client Configuration object
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public BookKeeper(final ClientConfiguration conf)
            throws IOException, InterruptedException, KeeperException {
        this.conf = conf;
        ZooKeeperWatcherBase w = new ZooKeeperWatcherBase(conf.getZkTimeout());
        this.zk = ZkUtils
                .createConnectedZookeeperClient(conf.getZkServers(), w);

        this.channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),
                                                                Executors.newCachedThreadPool());
        mainWorkerPool = new OrderedSafeExecutor(conf.getNumWorkerThreads());
        bookieClient = new BookieClient(conf, channelFactory, mainWorkerPool);
        bookieWatcher = new BookieWatcher(conf, this);
        bookieWatcher.readBookiesBlocking();

        ledgerManagerFactory = LedgerManagerFactory.newLedgerManagerFactory(conf, zk);
        ledgerManager = ledgerManagerFactory.newLedgerManager();

        ownChannelFactory = true;
        ownZKHandle = true;
    }

    /**
     * Create a bookkeeper client but use the passed in zookeeper client instead
     * of instantiating one.
     *
     * @param conf
     *          Client Configuration object
     *          {@link ClientConfiguration}
     * @param zk
     *          Zookeeper client instance connected to the zookeeper with which
     *          the bookies have registered
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    public BookKeeper(ClientConfiguration conf, ZooKeeper zk)
        throws IOException, InterruptedException, KeeperException {
        this(conf, zk, new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        ownChannelFactory = true;
    }

    /**
     * Create a bookkeeper client but use the passed in zookeeper client and
     * client socket channel factory instead of instantiating those.
     *
     * @param conf
     *          Client Configuration Object
     *          {@link ClientConfiguration}
     * @param zk
     *          Zookeeper client instance connected to the zookeeper with which
     *          the bookies have registered. The ZooKeeper client must be connected
     *          before it is passed to BookKeeper. Otherwise a KeeperException is thrown.
     * @param channelFactory
     *          A factory that will be used to create connections to the bookies
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException if the passed zk handle is not connected
     */
    public BookKeeper(ClientConfiguration conf, ZooKeeper zk, ClientSocketChannelFactory channelFactory)
            throws IOException, InterruptedException, KeeperException {
        if (zk == null || channelFactory == null) {
            throw new NullPointerException();
        }
        if (!zk.getState().isConnected()) {
            LOG.error("Unconnected zookeeper handle passed to bookkeeper");
            throw KeeperException.create(KeeperException.Code.CONNECTIONLOSS);
        }
        this.conf = conf;
        this.zk = zk;
        this.channelFactory = channelFactory;

        mainWorkerPool = new OrderedSafeExecutor(conf.getNumWorkerThreads());
        bookieClient = new BookieClient(conf, channelFactory, mainWorkerPool);
        bookieWatcher = new BookieWatcher(conf, this);
        bookieWatcher.readBookiesBlocking();

        ledgerManagerFactory = LedgerManagerFactory.newLedgerManagerFactory(conf, zk);
        ledgerManager = ledgerManagerFactory.newLedgerManager();
    }

    LedgerManager getLedgerManager() {
        return ledgerManager;
    }

    /**
     * There are 2 digest types that can be used for verification. The CRC32 is
     * cheap to compute but does not protect against byzantine bookies (i.e., a
     * bookie might report fake bytes and a matching CRC32). The MAC code is more
     * expensive to compute, but is protected by a password, i.e., a bookie can't
     * report fake bytes with a mathching MAC unless it knows the password
     */
    public enum DigestType {
        MAC, CRC32
    };

    ZooKeeper getZkHandle() {
        return zk;
    }

    protected ClientConfiguration getConf() {
        return conf;
    }

    /**
     * Get the BookieClient, currently used for doing bookie recovery.
     *
     * @return BookieClient for the BookKeeper instance.
     */
    BookieClient getBookieClient() {
        return bookieClient;
    }

    /**
     * Get the shared semaphore created by this bookkeeper client.
     * A new semaphore will be returned on each invocation.
     * This semaphore will have the throttle values
     * as specified by the configuration.
     * @return
     */
    BookKeeperSharedSemaphore getSharedSemaphore() {
        return new BookKeeperSharedSemaphore(conf.getThrottleValue(),
                conf.getReadThrottleValue());
    }

    /**
     * Creates a new ledger asynchronously. To create a ledger, we need to specify
     * the ensemble size, the quorum size, the digest type, a password, a callback
     * implementation, and an optional control object. The ensemble size is how
     * many bookies the entries should be striped among and the quorum size is the
     * degree of replication of each entry. The digest type is either a MAC or a
     * CRC. Note that the CRC option is not able to protect a client against a
     * bookie that replaces an entry. The password is used not only to
     * authenticate access to a ledger, but also to verify entries in ledgers.
     *
     * @param ensSize
     *          number of bookies over which to stripe entries
     * @param writeQuorumSize
     *          number of bookies each entry will be written to. each of these bookies
     *          must acknowledge the entry before the call is completed.
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @param cb
     *          createCallback implementation
     * @param ctx
     *          optional control object
     */
    public void asyncCreateLedger(final int ensSize,
                                  final int writeQuorumSize,
                                  final DigestType digestType,
                                  final byte[] passwd, final CreateCallback cb, final Object ctx)
    {
        asyncCreateLedger(ensSize, writeQuorumSize, writeQuorumSize, digestType, passwd, cb, ctx);
    }

    /**
     * Creates a new ledger asynchronously. Ledgers created with this call have
     * a separate write quorum and ack quorum size. The write quorum must be larger than
     * the ack quorum.
     *
     * Separating the write and the ack quorum allows the BookKeeper client to continue
     * writing when a bookie has failed but the failure has not yet been detected. Detecting
     * a bookie has failed can take a number of seconds, as configured by the read timeout
     * {@link ClientConfiguration#getReadTimeout()}. Once the bookie failure is detected,
     * that bookie will be removed from the ensemble.
     *
     * The other parameters match those of {@link #asyncCreateLedger(int, int, DigestType, byte[],
     *                                      AsyncCallback.CreateCallback, Object)}
     *
     * @param ensSize
     *          number of bookies over which to stripe entries
     * @param writeQuorumSize
     *          number of bookies each entry will be written to
     * @param ackQuorumSize
     *          number of bookies which must acknowledge an entry before the call is completed
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @param cb
     *          createCallback implementation
     * @param ctx
     *          optional control object
     */

    public void asyncCreateLedger(final int ensSize,
                                  final int writeQuorumSize,
                                  final int ackQuorumSize,
                                  final DigestType digestType,
                                  final byte[] passwd, final CreateCallback cb, final Object ctx) {
        if (writeQuorumSize < ackQuorumSize) {
            throw new IllegalArgumentException("Write quorum must be larger than ack quorum");
        }
        new LedgerCreateOp(BookKeeper.this, ensSize, writeQuorumSize,
                           ackQuorumSize, digestType, passwd, cb, ctx)
            .initiate();
    }


    /**
     * Creates a new ledger. Default of 3 servers, and quorum of 2 servers.
     *
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @return a handle to the newly created ledger
     * @throws InterruptedException
     * @throws BKException
     */
    public LedgerHandle createLedger(DigestType digestType, byte passwd[])
            throws BKException, InterruptedException {
        return createLedger(3, 2, digestType, passwd);
    }

    /**
     * Synchronous call to create ledger. Parameters match those of
     * {@link #asyncCreateLedger(int, int, DigestType, byte[],
     *                           AsyncCallback.CreateCallback, Object)}
     *
     * @param ensSize
     * @param qSize
     * @param digestType
     * @param passwd
     * @return a handle to the newly created ledger
     * @throws InterruptedException
     * @throws BKException
     */
    public LedgerHandle createLedger(int ensSize, int qSize,
                                     DigestType digestType, byte passwd[])
            throws InterruptedException, BKException {
        return createLedger(ensSize, qSize, qSize, digestType, passwd);
    }

    /**
     * Synchronous call to create ledger. Parameters match those of
     * {@link #asyncCreateLedger(int, int, int, DigestType, byte[],
     *                           AsyncCallback.CreateCallback, Object)}
     *
     * @param ensSize
     * @param writeQuorumSize
     * @param ackQuorumSize
     * @param digestType
     * @param passwd
     * @return a handle to the newly created ledger
     * @throws InterruptedException
     * @throws BKException
     */
    public LedgerHandle createLedger(int ensSize, int writeQuorumSize, int ackQuorumSize,
                                     DigestType digestType, byte passwd[])
            throws InterruptedException, BKException {
        SyncCounter counter = new SyncCounter();
        counter.inc();
        /*
         * Calls asynchronous version
         */
        asyncCreateLedger(ensSize, writeQuorumSize, ackQuorumSize, digestType, passwd,
                          new SyncCreateCallback(), counter);

        /*
         * Wait
         */
        counter.block(0);
        if (counter.getLh() == null) {
            LOG.error("ZooKeeper error: " + counter.getrc());
            throw BKException.create(Code.ZKException);
        }

        return counter.getLh();
    }

    /**
     * Open existing ledger asynchronously for reading.
     *
     * Opening a ledger with this method invokes fencing and recovery on the ledger
     * if the ledger has not been closed. Fencing will block all other clients from
     * writing to the ledger. Recovery will make sure that the ledger is closed
     * before reading from it.
     *
     * Recovery also makes sure that any entries which reached one bookie, but not a
     * quorum, will be replicated to a quorum of bookies. This occurs in cases were
     * the writer of a ledger crashes after sending a write request to one bookie but
     * before being able to send it to the rest of the bookies in the quorum.
     *
     * If the ledger is already closed, neither fencing nor recovery will be applied.
     *
     * @see LedgerHandle#asyncClose
     *
     * @param lId
     *          ledger identifier
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @param ctx
     *          optional control object
     */
    public void asyncOpenLedger(final long lId, final DigestType digestType, final byte passwd[],
                                final OpenCallback cb, final Object ctx) {
        new LedgerOpenOp(BookKeeper.this, lId, digestType, passwd, cb, ctx).initiate();
    }

    /**
     * Open existing ledger asynchronously for reading, but it does not try to
     * recover the ledger if it is not yet closed. The application needs to use
     * it carefully, since the writer might have crashed and ledger will remain
     * unsealed forever if there is no external mechanism to detect the failure
     * of the writer and the ledger is not open in a safe manner, invoking the
     * recovery procedure.
     *
     * Opening a ledger without recovery does not fence the ledger. As such, other
     * clients can continue to write to the ledger.
     *
     * This method returns a read only ledger handle. It will not be possible
     * to add entries to the ledger. Any attempt to add entries will throw an
     * exception.
     *
     * Reads from the returned ledger will only be able to read entries up until
     * the lastConfirmedEntry at the point in time at which the ledger was opened.
     *
     * @param lId
     *          ledger identifier
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @param ctx
     *          optional control object
     */
    public void asyncOpenLedgerNoRecovery(final long lId, final DigestType digestType, final byte passwd[],
                                          final OpenCallback cb, final Object ctx) {
        new LedgerOpenOp(BookKeeper.this, lId, digestType, passwd, cb, ctx).initiateWithoutRecovery();
    }


    /**
     * Synchronous open ledger call
     *
     * @see #asyncOpenLedger
     * @param lId
     *          ledger identifier
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @return a handle to the open ledger
     * @throws InterruptedException
     * @throws BKException
     */

    public LedgerHandle openLedger(long lId, DigestType digestType, byte passwd[])
            throws BKException, InterruptedException {
        SyncCounter counter = new SyncCounter();
        counter.inc();

        /*
         * Calls async open ledger
         */
        asyncOpenLedger(lId, digestType, passwd, new SyncOpenCallback(), counter);

        /*
         * Wait
         */
        counter.block(0);
        if (counter.getrc() != BKException.Code.OK)
            throw BKException.create(counter.getrc());

        return counter.getLh();
    }

    /**
     * Synchronous, unsafe open ledger call
     *
     * @see #asyncOpenLedgerNoRecovery
     * @param lId
     *          ledger identifier
     * @param digestType
     *          digest type, either MAC or CRC32
     * @param passwd
     *          password
     * @return a handle to the open ledger
     * @throws InterruptedException
     * @throws BKException
     */

    public LedgerHandle openLedgerNoRecovery(long lId, DigestType digestType, byte passwd[])
            throws BKException, InterruptedException {
        SyncCounter counter = new SyncCounter();
        counter.inc();

        /*
         * Calls async open ledger
         */
        asyncOpenLedgerNoRecovery(lId, digestType, passwd,
                                  new SyncOpenCallback(), counter);

        /*
         * Wait
         */
        counter.block(0);
        if (counter.getrc() != BKException.Code.OK)
            throw BKException.create(counter.getrc());

        return counter.getLh();
    }

    /**
     * Deletes a ledger asynchronously.
     *
     * @param lId
     *            ledger Id
     * @param cb
     *            deleteCallback implementation
     * @param ctx
     *            optional control object
     */
    public void asyncDeleteLedger(final long lId, final DeleteCallback cb, final Object ctx) {
        new LedgerDeleteOp(BookKeeper.this, lId, cb, ctx).initiate();
    }


    /**
     * Synchronous call to delete a ledger. Parameters match those of
     * {@link #asyncDeleteLedger(long, AsyncCallback.DeleteCallback, Object)}
     *
     * @param lId
     *            ledgerId
     * @throws InterruptedException
     * @throws BKException.BKNoSuchLedgerExistsException if the ledger doesn't exist
     * @throws BKException
     */
    public void deleteLedger(long lId) throws InterruptedException, BKException {
        SyncCounter counter = new SyncCounter();
        counter.inc();
        // Call asynchronous version
        asyncDeleteLedger(lId, new SyncDeleteCallback(), counter);
        // Wait
        counter.block(0);
        if (counter.getrc() != BKException.Code.OK) {
            LOG.error("Error deleting ledger " + lId + " : " + counter.getrc());
            throw BKException.create(Code.ZKException);
        }
    }

    /**
     * Get the stats logger
     */
    public BookkeeperClientStatsLogger getStatsLogger() {
        return this.statsLogger;
    }

    /**
     * Shuts down client.
     *
     */
    public void close() throws InterruptedException, BKException {
        bookieClient.close();
        try {
            ledgerManager.close();
            ledgerManagerFactory.uninitialize();
        } catch (IOException ie) {
            LOG.error("Failed to close ledger manager : ", ie);
        }
        bookieWatcher.halt();
        if (ownChannelFactory) {
            channelFactory.releaseExternalResources();
        }
        if (ownZKHandle) {
            zk.close();
        }
        mainWorkerPool.shutdown();
    }

    private static class SyncCreateCallback implements CreateCallback {
        /**
         * Create callback implementation for synchronous create call.
         *
         * @param rc
         *          return code
         * @param lh
         *          ledger handle object
         * @param ctx
         *          optional control object
         */
        public void createComplete(int rc, LedgerHandle lh, Object ctx) {
            SyncCounter counter = (SyncCounter) ctx;
            counter.setLh(lh);
            counter.setrc(rc);
            counter.dec();
        }
    }

    static class SyncOpenCallback implements OpenCallback {
        /**
         * Callback method for synchronous open operation
         *
         * @param rc
         *          return code
         * @param lh
         *          ledger handle
         * @param ctx
         *          optional control object
         */
        public void openComplete(int rc, LedgerHandle lh, Object ctx) {
            SyncCounter counter = (SyncCounter) ctx;
            counter.setLh(lh);

            LOG.debug("Open complete: " + rc);

            counter.setrc(rc);
            counter.dec();
        }
    }

    private static class SyncDeleteCallback implements DeleteCallback {
        /**
         * Delete callback implementation for synchronous delete call.
         *
         * @param rc
         *            return code
         * @param ctx
         *            optional control object
         */
        public void deleteComplete(int rc, Object ctx) {
            SyncCounter counter = (SyncCounter) ctx;
            counter.setrc(rc);
            counter.dec();
        }
    }



}

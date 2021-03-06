/*
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

package org.apache.bookkeeper.bookie;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.bookkeeper.bookie.Bookie.NoLedgerException;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.meta.ActiveLedgerManager;
import org.apache.bookkeeper.meta.LedgerManagerFactory;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.TestCase;

/**
 * LedgerCache related test cases
 */
public class LedgerCacheTest extends TestCase {
    static Logger LOG = LoggerFactory.getLogger(LedgerCacheTest.class);

    ActiveLedgerManager activeLedgerManager;
    LedgerManagerFactory ledgerManagerFactory;
    LedgerCache ledgerCache;
    ServerConfiguration conf;
    File txnDir, ledgerDir;

    private Bookie bookie;

    @Override
    @Before
    public void setUp() throws Exception {
        txnDir = File.createTempFile("ledgercache", "txn");
        txnDir.delete();
        txnDir.mkdir();
        ledgerDir = File.createTempFile("ledgercache", "ledger");
        ledgerDir.delete();
        ledgerDir.mkdir();
        // create current dir
        new File(ledgerDir, Bookie.CURRENT_DIR).mkdir();

        conf = new ServerConfiguration();
        conf.setZkServers(null);
        conf.setJournalDirName(txnDir.getPath());
        conf.setLedgerDirNames(new String[] { ledgerDir.getPath() });
        bookie = new Bookie(conf);

        ledgerManagerFactory =
            LedgerManagerFactory.newLedgerManagerFactory(conf, null);
        activeLedgerManager = ledgerManagerFactory.newActiveLedgerManager();
        ledgerCache = ((InterleavedLedgerStorage) bookie.ledgerStorage).ledgerCache;
    }

    @Override
    @After
    public void tearDown() throws Exception {
        bookie.ledgerStorage.shutdown();
        activeLedgerManager.close();
        ledgerManagerFactory.uninitialize();
        FileUtils.deleteDirectory(txnDir);
        FileUtils.deleteDirectory(ledgerDir);
    }

    private void newLedgerCache() throws IOException {
        if (ledgerCache != null) {
            ledgerCache.close();
        }
        ledgerCache = ((InterleavedLedgerStorage) bookie.ledgerStorage).ledgerCache = new LedgerCacheImpl(
                conf, activeLedgerManager, bookie.getLedgerDirsManager());
    }

    @Test
    public void testAddEntryException() throws IOException {
        // set page limitation
        conf.setPageLimit(10);
        // create a ledger cache
        newLedgerCache();
        /*
         * Populate ledger cache.
         */
        try {
            byte[] masterKey = "blah".getBytes();
            for( int i = 0; i < 100; i++) {
                ledgerCache.setMasterKey((long)i, masterKey);
                ledgerCache.putEntryOffset(i, 0, i*8);
            }
        } catch (IOException e) {
            LOG.error("Got IOException.", e);
            fail("Failed to add entry.");
        }
    }

    @Test
    public void testLedgerEviction() throws Exception {
        int numEntries = 10;
        // limit open files & pages
        conf.setOpenFileLimit(1).setPageLimit(2)
            .setPageSize(8 * numEntries);
        // create ledger cache
        newLedgerCache();
        try {
            int numLedgers = 3;
            byte[] masterKey = "blah".getBytes();
            for (int i=1; i<=numLedgers; i++) {
                ledgerCache.setMasterKey((long)i, masterKey);
                for (int j=0; j<numEntries; j++) {
                    ledgerCache.putEntryOffset(i, j, i * numEntries + j);
                }
            }
        } catch (Exception e) {
            LOG.error("Got Exception.", e);
            fail("Failed to add entry.");
        }
    }

    @Test
    public void testDeleteLedger() throws Exception {
        int numEntries = 10;
        // limit open files & pages
        conf.setOpenFileLimit(999).setPageLimit(2)
            .setPageSize(8 * numEntries);
        // create ledger cache
        newLedgerCache();
        try {
            int numLedgers = 2;
            byte[] masterKey = "blah".getBytes();
            for (int i=1; i<=numLedgers; i++) {
                ledgerCache.setMasterKey((long)i, masterKey);
                for (int j=0; j<numEntries; j++) {
                    ledgerCache.putEntryOffset(i, j, i*numEntries + j);
                }
            }
            // ledger cache is exhausted
            // delete ledgers
            for (int i=1; i<=numLedgers; i++) {
                ledgerCache.deleteLedger((long)i);
            }
            // create num ledgers to add entries
            for (int i=numLedgers+1; i<=2*numLedgers; i++) {
                ledgerCache.setMasterKey((long)i, masterKey);
                for (int j=0; j<numEntries; j++) {
                    ledgerCache.putEntryOffset(i, j, i*numEntries + j);
                }
            }
        } catch (Exception e) {
            LOG.error("Got Exception.", e);
            fail("Failed to add entry.");
        }
    }

    @Test
    public void testPageEviction() throws Exception {
        int numLedgers = 10;
        byte[] masterKey = "blah".getBytes();
        // limit page count
        conf.setOpenFileLimit(999999).setPageLimit(3);
        // create ledger cache
        newLedgerCache();
        try {
            // create serveral ledgers
            for (int i=1; i<=numLedgers; i++) {
                ledgerCache.setMasterKey((long)i, masterKey);
                ledgerCache.putEntryOffset(i, 0, i*8);
                ledgerCache.putEntryOffset(i, 1, i*8);
            }

            // flush all first to clean previous dirty ledgers
            ledgerCache.flushLedger(true);
            // flush all 
            ledgerCache.flushLedger(true);

            // delete serveral ledgers
            for (int i=1; i<=numLedgers/2; i++) {
                ledgerCache.deleteLedger(i);
            }

            // bookie restarts
            newLedgerCache();

            // simulate replaying journals to add entries again
            for (int i=1; i<=numLedgers; i++) {
                try {
                    ledgerCache.putEntryOffset(i, 1, i*8);
                } catch (NoLedgerException nsle) {
                    if (i<=numLedgers/2) {
                        // it is ok
                    } else {
                        LOG.error("Error put entry offset : ", nsle);
                        fail("Should not reach here.");
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Got Exception.", e);
            fail("Failed to add entry.");
        }
    }

    /**
     * Test Ledger Cache flush failure
     */
    public void testLedgerCacheFlushFailureOnDiskFull() throws Exception {
        File ledgerDir1 = File.createTempFile("bkTest", ".dir");
        ledgerDir1.delete();
        File ledgerDir2 = File.createTempFile("bkTest", ".dir");
        ledgerDir2.delete();
        ServerConfiguration conf = new ServerConfiguration();
        conf.setLedgerDirNames(new String[] { ledgerDir1.getAbsolutePath(), ledgerDir2.getAbsolutePath() });

        Bookie bookie = new Bookie(conf);
        InterleavedLedgerStorage ledgerStorage = ((InterleavedLedgerStorage) bookie.ledgerStorage);
        LedgerCacheImpl ledgerCache = (LedgerCacheImpl) ledgerStorage.ledgerCache;
        // Create ledger index file
        ledgerStorage.setMasterKey(1, "key".getBytes());

        FileInfo fileInfo = ledgerCache.indexPersistenceManager.getFileInfo(Long.valueOf(1), null);

        // Simulate the flush failure
        FileInfo newFileInfo = new FileInfo(fileInfo.getLf(), fileInfo.getMasterKey());
        ledgerCache.indexPersistenceManager.fileInfoCache.put(Long.valueOf(1), newFileInfo);
        // Add entries
        ledgerStorage.addEntry(generateEntry(1, 1));
        ledgerStorage.addEntry(generateEntry(1, 2));
        ledgerStorage.prepare(true);
        ledgerStorage.flush();

        // add the dir to failed dirs
        bookie.getLedgerDirsManager().addToFilledDirs(
                newFileInfo.getLf().getParentFile().getParentFile().getParentFile());
        File before = newFileInfo.getLf();
        // flush after disk is added as failed.
        ledgerStorage.addEntry(generateEntry(1, 3));
        ledgerStorage.prepare(true);
        ledgerStorage.flush();
        File after = newFileInfo.getLf();

        assertFalse("After flush index file should be changed", before.equals(after));
        // Verify written entries
        Assert.assertArrayEquals(generateEntry(1, 1).array(), ledgerStorage.getEntry(1, 1).array());
        Assert.assertArrayEquals(generateEntry(1, 2).array(), ledgerStorage.getEntry(1, 2).array());
        Assert.assertArrayEquals(generateEntry(1, 3).array(), ledgerStorage.getEntry(1, 3).array());
    }

    private ByteBuffer generateEntry(long ledger, long entry) {
        byte[] data = ("ledger-" + ledger + "-" + entry).getBytes();
        ByteBuffer bb = ByteBuffer.wrap(new byte[8 + 8 + data.length]);
        bb.putLong(ledger);
        bb.putLong(entry);
        bb.put(data);
        bb.flip();
        return bb;
    }
}

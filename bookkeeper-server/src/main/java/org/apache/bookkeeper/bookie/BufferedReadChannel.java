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

import org.apache.bookkeeper.bookie.BufferedChannelBase;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.lang.Math;


import org.apache.bookkeeper.stats.BookkeeperServerStatsLogger;
import org.apache.bookkeeper.stats.ServerStatsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Buffered channel without a write buffer. Only reads are buffered.
 */
public class BufferedReadChannel extends BufferedChannelBase {
    private static Logger LOG = LoggerFactory.getLogger(BufferedReadChannel.class);
    // The capacity of the read buffer.
    protected final int readCapacity;
    // The buffer for read operations.
    protected ByteBuffer readBuffer;
    // The starting position of the data currently in the read buffer.
    protected long readBufferStartPosition = Long.MIN_VALUE;

    long invocationCount = 0;
    long cacheHitCount = 0;

    public BufferedReadChannel(FileChannel fileChannel, int readCapacity) throws IOException {
        super(fileChannel);
        this.readCapacity = readCapacity;
        this.readBuffer = ByteBuffer.allocateDirect(readCapacity);
        this.readBuffer.limit(0);
    }

    /**
     * Read as many bytes into dest as dest.capacity() starting at position pos in the
     * FileChannel. This function can read from the buffer or the file channel
     * depending on the implementation..
     * @param dest
     * @param pos
     * @return The total number of bytes read.
     * @throws IOException if a read operation fails or in case of a short read.
     */
    synchronized public int read(ByteBuffer dest, long pos) throws IOException {
        invocationCount++;
        long currentPosition = pos;
        while (dest.remaining() > 0) {
            // Check if the data is in the buffer, if so, copy it.
            if (readBufferStartPosition <= currentPosition && currentPosition < readBufferStartPosition + readBuffer.limit()) {
                long posInBuffer = currentPosition - readBufferStartPosition;
                long bytesToCopy = Math.min(dest.remaining(), readBuffer.limit() - posInBuffer);
                ByteBuffer rbDup = readBuffer.duplicate();
                rbDup.position((int)posInBuffer);
                rbDup.limit((int)(posInBuffer + bytesToCopy));
                dest.put(rbDup);
                currentPosition += bytesToCopy;
                cacheHitCount++;
            } else {
                // We don't have it in the buffer, so put necessary data in the buffer
                readBuffer.clear();
                readBufferStartPosition = currentPosition;
                int readBytes = 0;
                if ((readBytes = validateAndGetFileChannel().read(readBuffer, currentPosition)) <= 0) {
                    throw new IOException("Reading from filechannel returned a non-positive value. Short read.");
                }
                readBuffer.limit(readBytes);
            }
        }
        return (int)(currentPosition - pos);
    }

    synchronized public void clear() {
        readBuffer.clear();
        readBuffer.limit(0);
    }

    protected void finalize () {
        ServerStatsProvider.getStatsLoggerInstance().getSimpleStatLogger(
            BookkeeperServerStatsLogger.BookkeeperServerSimpleStatType.BUFFERED_READER_NUM_READ_REQUESTS)
            .add(invocationCount);
        ServerStatsProvider.getStatsLoggerInstance().getSimpleStatLogger(
            BookkeeperServerStatsLogger.BookkeeperServerSimpleStatType.BUFFERED_READER_NUM_READ_CACHE_HITS)
            .add(cacheHitCount);
    }
}

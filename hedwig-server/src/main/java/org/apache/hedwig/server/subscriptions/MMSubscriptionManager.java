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
package org.apache.hedwig.server.subscriptions;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import com.google.protobuf.ByteString;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.protocol.PubSubProtocol.SubscriptionData;
import org.apache.hedwig.server.common.ServerConfiguration;
import org.apache.hedwig.server.delivery.DeliveryManager;
import org.apache.hedwig.server.meta.MetadataManagerFactory;
import org.apache.hedwig.server.meta.SubscriptionDataManager;
import org.apache.hedwig.server.persistence.PersistenceManager;
import org.apache.hedwig.server.topics.TopicManager;
import org.apache.hedwig.util.Callback;

/**
 * MetaManager-based subscription manager.
 */
public class MMSubscriptionManager extends AbstractSubscriptionManager {

    SubscriptionDataManager subManager;

    public MMSubscriptionManager(MetadataManagerFactory metaManagerFactory,
                                 TopicManager topicMgr, PersistenceManager pm,
                                 ServerConfiguration cfg,
                                 ScheduledExecutorService scheduler) {
        super(cfg, topicMgr, pm, scheduler);
        this.subManager = metaManagerFactory.newSubscriptionDataManager();
    }

    @Override
    protected void readSubscriptions(final ByteString topic,
                                     final Callback<Map<ByteString, InMemorySubscriptionState>> cb, final Object ctx) {
        subManager.readSubscriptions(topic, new Callback<Map<ByteString, SubscriptionData>>() {
            @Override
            public void operationFailed(Object ctx, PubSubException pse) {
                cb.operationFailed(ctx, pse);
            }
            @Override
            public void operationFinished(Object ctx, Map<ByteString, SubscriptionData> subs) {
                Map<ByteString, InMemorySubscriptionState> results = new ConcurrentHashMap<ByteString, InMemorySubscriptionState>();
                for (Map.Entry<ByteString, SubscriptionData> subEntry : subs.entrySet()) {
                    results.put(subEntry.getKey(), new InMemorySubscriptionState(subEntry.getValue()));
                }
                cb.operationFinished(ctx, results);
            }
        }, ctx);
    }

    @Override
    protected boolean isPartialUpdateSupported() {
        return subManager.isPartialUpdateSupported();
    }

    @Override
    protected void createSubscriptionData(final ByteString topic, final ByteString subscriberId,
                                          final SubscriptionData subData, final Callback<Void> callback, final Object ctx) {
        subManager.createSubscriptionData(topic, subscriberId, subData, callback, ctx);
    }

    @Override
    protected void replaceSubscriptionData(final ByteString topic, final ByteString subscriberId,
                                           final SubscriptionData subData, final Callback<Void> callback, final Object ctx) {
        subManager.replaceSubscriptionData(topic, subscriberId, subData, callback, ctx);
    }

    @Override
    protected void updateSubscriptionData(final ByteString topic, final ByteString subscriberId,
                                          final SubscriptionData subData, final Callback<Void> callback, final Object ctx) {
        subManager.updateSubscriptionData(topic, subscriberId, subData, callback, ctx);
    }

    @Override
    protected void deleteSubscriptionData(final ByteString topic, final ByteString subscriberId,
                                          final Callback<Void> callback, final Object ctx) {
        subManager.deleteSubscriptionData(topic, subscriberId, callback, ctx);
    }

    @Override
    public void stop() {
        super.stop();
        try {
            subManager.close();
        } catch (IOException ioe) {
            logger.warn("Exception closing subscription data manager : ", ioe);
        }
    }
}

/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.qos.client;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.palantir.atlasdb.qos.QosClient;
import com.palantir.atlasdb.qos.QueryWeight;
import com.palantir.atlasdb.qos.metrics.QosMetrics;
import com.palantir.atlasdb.qos.ratelimit.QosRateLimiter;
import com.palantir.atlasdb.qos.ratelimit.QosRateLimiters;

public class AtlasDbQosClient implements QosClient {

    private static final Logger log = LoggerFactory.getLogger(AtlasDbQosClient.class);

    private final QosRateLimiters rateLimiters;
    private final QosMetrics metrics;
    private final Ticker ticker;

    public static AtlasDbQosClient create(QosRateLimiters rateLimiters) {
        return new AtlasDbQosClient(rateLimiters, new QosMetrics(), Ticker.systemTicker());
    }

    @VisibleForTesting
    AtlasDbQosClient(QosRateLimiters rateLimiters, QosMetrics metrics, Ticker ticker) {
        this.metrics = metrics;
        this.rateLimiters = rateLimiters;
        this.ticker = ticker;
    }

    @Override
    public <T, E extends Exception> T executeRead(Query<T, E> query, QueryWeigher<T> weigher) throws E {
        return execute(query, weigher, rateLimiters.read(), metrics::recordRead);
    }

    @Override
    public <T, E extends Exception> T executeWrite(Query<T, E> query, QueryWeigher<T> weigher) throws E {
        return execute(query, weigher, rateLimiters.write(), metrics::recordWrite);
    }

    private <T, E extends Exception> T execute(
            Query<T, E> query,
            QueryWeigher<T> weigher,
            QosRateLimiter rateLimiter,
            Consumer<QueryWeight> weightMetric) throws E {
        long estimatedNumBytes = weigher.estimate().numBytes();
        rateLimiter.consumeWithBackoff(estimatedNumBytes);

        // TODO(nziebart): decide what to do if we encounter a timeout exception
        long startTimeNanos = ticker.read();
        T result = query.execute();
        long totalTimeNanos = ticker.read() - startTimeNanos;

        QueryWeight actualWeight = weigher.weigh(result, totalTimeNanos);
        weightMetric.accept(actualWeight);
        rateLimiter.recordAdjustment(actualWeight.numBytes() - estimatedNumBytes);

        return result;
    }

}
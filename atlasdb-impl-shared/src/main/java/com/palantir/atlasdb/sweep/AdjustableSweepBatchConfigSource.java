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

package com.palantir.atlasdb.sweep;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Supplier;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.logsafe.SafeArg;

public final class AdjustableSweepBatchConfigSource {
    private static final Logger log = LoggerFactory.getLogger(BackgroundSweeperImpl.class);

    private final Supplier<SweepBatchConfig> rawSweepBatchConfig;

    private static volatile double batchSizeMultiplier = 1.0;
    private final AtomicInteger successiveIncreases = new AtomicInteger(0);

    private AdjustableSweepBatchConfigSource(Supplier<SweepBatchConfig> rawSweepBatchConfig) {
        this.rawSweepBatchConfig = rawSweepBatchConfig;
    }

    public static AdjustableSweepBatchConfigSource create(Supplier<SweepBatchConfig> rawSweepBatchConfig) {
        AdjustableSweepBatchConfigSource configSource = new AdjustableSweepBatchConfigSource(rawSweepBatchConfig);

        new MetricsManager().registerMetric(AdjustableSweepBatchConfigSource.class, "batchSizeMultiplier",
                () -> getBatchSizeMultiplier());

        return configSource;
    }

    public SweepBatchConfig getRawSweepConfig() {
        return rawSweepBatchConfig.get();
    }

    public static double getBatchSizeMultiplier() {
        return batchSizeMultiplier;
    }

    public SweepBatchConfig getAdjustedSweepConfig() {
        SweepBatchConfig sweepConfig = getRawSweepConfig();
        double multiplier = batchSizeMultiplier;

        return ImmutableSweepBatchConfig.builder()
                .maxCellTsPairsToExamine(adjust(sweepConfig.maxCellTsPairsToExamine(), multiplier))
                .candidateBatchSize(adjust(sweepConfig.candidateBatchSize(), multiplier))
                .deleteBatchSize(adjust(sweepConfig.deleteBatchSize(), multiplier))
                .build();
    }

    private static int adjust(int parameterValue, double multiplier) {
        return Math.max(1, (int) (multiplier * parameterValue));
    }

    public void increaseMultiplier() {
        if (batchSizeMultiplier == 1.0) {
            return;
        }

        if (successiveIncreases.incrementAndGet() > 25) {
            batchSizeMultiplier = Math.min(1.0, batchSizeMultiplier * 2);
        }
    }

    public void decreaseMultiplier() {
        successiveIncreases.set(0);
        SweepBatchConfig lastBatchConfig = getAdjustedSweepConfig();

        // Cut batch size in half, always sweep at least one row.
        reduceBatchSizeMultiplier();

        log.info("Sweep failed unexpectedly with candidate batch size {},"
                        + " delete batch size {},"
                        + " and {} cell+timestamp pairs to examine."
                        + " Attempting to continue with new batchSizeMultiplier {}",
                SafeArg.of("candidateBatchSize", lastBatchConfig.candidateBatchSize()),
                SafeArg.of("deleteBatchSize", lastBatchConfig.deleteBatchSize()),
                SafeArg.of("maxCellTsPairsToExamine", lastBatchConfig.maxCellTsPairsToExamine()),
                SafeArg.of("batchSizeMultiplier", batchSizeMultiplier));
    }

    private void reduceBatchSizeMultiplier() {
        SweepBatchConfig config = getRawSweepConfig();
        double smallestSensibleBatchSizeMultiplier =
                1.0 / NumberUtils.max(
                        config.maxCellTsPairsToExamine(), config.candidateBatchSize(), config.deleteBatchSize());

        if (batchSizeMultiplier == smallestSensibleBatchSizeMultiplier) {
            return;
        }

        double newBatchSizeMultiplier = batchSizeMultiplier / 2;
        if (newBatchSizeMultiplier < smallestSensibleBatchSizeMultiplier) {
            log.info("batchSizeMultiplier reached the smallest sensible value for the current sweep config ({}), "
                            + "will not reduce further.",
                    SafeArg.of("batchSizeMultiplier", smallestSensibleBatchSizeMultiplier));
            batchSizeMultiplier = smallestSensibleBatchSizeMultiplier;
        } else {
            batchSizeMultiplier = newBatchSizeMultiplier;
        }
    }
}

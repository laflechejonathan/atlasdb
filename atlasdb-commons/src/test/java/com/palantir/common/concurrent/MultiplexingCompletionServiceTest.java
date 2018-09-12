/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
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

package com.palantir.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;

import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

public class MultiplexingCompletionServiceTest {
    private static final String KEY_1 = "key_1";
    private static final String KEY_2 = "key_2";

    private final DeterministicScheduler executor1 = new DeterministicScheduler();
    private final DeterministicScheduler executor2 = new DeterministicScheduler();

    @Test
    public void executorServicesFeedInToTheSameQueue() throws ExecutionException, InterruptedException {
        MultiplexingCompletionService<String, Integer> service = MultiplexingCompletionService.create(
                ImmutableMap.of(KEY_1, executor1, KEY_2, executor2));
        service.submit(KEY_1, () -> 31);
        service.submit(KEY_2, () -> 41);

        executor1.runUntilIdle();
        executor2.runUntilIdle();

        assertThat(service.poll().get()).isEqualTo(31);
        assertThat(service.poll().get()).isEqualTo(41);
    }

    @Test
    public void resultsAreTakenAsTheyBecomeAvailable() throws ExecutionException, InterruptedException {
        MultiplexingCompletionService<String, Integer> service = MultiplexingCompletionService.create(
                ImmutableMap.of(KEY_1, executor1, KEY_2, executor2));
        service.submit(KEY_1, () -> 5);
        service.submit(KEY_2, () -> 11);
        service.submit(KEY_1, () -> 42);

        executor1.runUntilIdle();
        executor2.runUntilIdle();

        // 42 is before 11, because executor 1 finishes its tasks first
        assertThat(service.poll().get()).isEqualTo(5);
        assertThat(service.poll().get()).isEqualTo(42);
        assertThat(service.poll().get()).isEqualTo(11);
        assertThat(service.poll()).isNull();
    }

    @Test
    public void propagatesFailingComputationResults() throws ExecutionException, InterruptedException {
        MultiplexingCompletionService<String, Integer> service = MultiplexingCompletionService.create(
                ImmutableMap.of(KEY_1, executor1, KEY_2, executor2));
        service.submit(KEY_1, () -> 5);
        service.submit(KEY_2, () -> {
            throw new IllegalArgumentException("bad");
        });

        executor1.runUntilIdle();
        executor2.runUntilIdle();

        assertThat(service.poll().get()).isEqualTo(5);
        assertThatThrownBy(() -> service.poll().get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void throwsIfKeyDoesNotExist() {
        MultiplexingCompletionService<String, Integer> service = MultiplexingCompletionService.create(
                ImmutableMap.of(KEY_1, executor1));
        assertThatThrownBy(() -> service.submit(KEY_2, () -> 7)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void returnsResultsEvenIfOneExecutorIsSlow() throws ExecutionException, InterruptedException {
        MultiplexingCompletionService<String, Integer> service = MultiplexingCompletionService.create(
                ImmutableMap.of(KEY_1, executor1, KEY_2, executor2));
        service.submit(KEY_1, () -> 5);
        service.submit(KEY_2, () -> 11);
        service.submit(KEY_1, () -> 42);
        service.submit(KEY_2, () -> 18);

        // executor1 does not run any of its tasks
        executor2.runUntilIdle();

        assertThat(service.poll().get()).isEqualTo(11);
        assertThat(service.poll().get()).isEqualTo(18);
        assertThat(service.poll()).isNull();

        executor1.runUntilIdle();

        assertThat(service.poll().get()).isEqualTo(5);
        assertThat(service.poll().get()).isEqualTo(42);
        assertThat(service.poll()).isNull();
    }
}

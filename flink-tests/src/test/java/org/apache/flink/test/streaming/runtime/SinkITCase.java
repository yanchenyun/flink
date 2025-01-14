/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.streaming.runtime;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.IntegerTypeInfo;
import org.apache.flink.api.connector.sink.Sink;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.metrics.Metric;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.metrics.groups.SinkWriterMetricGroup;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.testutils.InMemoryReporterRule;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.runtime.operators.sink.TestSink;
import org.apache.flink.streaming.util.FiniteTestSource;
import org.apache.flink.test.util.AbstractTestBase;
import org.apache.flink.testutils.junit.SharedObjects;
import org.apache.flink.testutils.junit.SharedReference;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static java.util.stream.Collectors.joining;
import static org.apache.flink.metrics.testutils.MetricMatchers.isCounter;
import static org.apache.flink.metrics.testutils.MetricMatchers.isGauge;
import static org.apache.flink.streaming.runtime.operators.sink.TestSink.END_OF_INPUT_STR;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

/**
 * Integration test for {@link org.apache.flink.api.connector.sink.Sink} run time implementation.
 */
public class SinkITCase extends AbstractTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(SinkITCase.class);
    @Rule public final SharedObjects sharedObjects = SharedObjects.create();
    @Rule public final InMemoryReporterRule inMemoryReporter = InMemoryReporterRule.create();

    static final List<Integer> SOURCE_DATA =
            Arrays.asList(
                    895, 127, 148, 161, 148, 662, 822, 491, 275, 122, 850, 630, 682, 765, 434, 970,
                    714, 795, 288, 422);

    // source send data two times
    static final int STREAMING_SOURCE_SEND_ELEMENTS_NUM = SOURCE_DATA.size() * 2;

    static final List<String> EXPECTED_COMMITTED_DATA_IN_STREAMING_MODE =
            SOURCE_DATA.stream()
                    // source send data two times
                    .flatMap(
                            x ->
                                    Collections.nCopies(
                                            2, Tuple3.of(x, null, Long.MIN_VALUE).toString())
                                            .stream())
                    .collect(Collectors.toList());

    static final List<String> EXPECTED_COMMITTED_DATA_IN_BATCH_MODE =
            SOURCE_DATA.stream()
                    .map(x -> Tuple3.of(x, null, Long.MIN_VALUE).toString())
                    .collect(Collectors.toList());

    static final List<String> EXPECTED_GLOBAL_COMMITTED_DATA_IN_STREAMING_MODE =
            SOURCE_DATA.stream()
                    // source send data two times
                    .flatMap(
                            x ->
                                    Collections.nCopies(
                                            2, Tuple3.of(x, null, Long.MIN_VALUE).toString())
                                            .stream())
                    .collect(Collectors.toList());

    static final List<String> EXPECTED_GLOBAL_COMMITTED_DATA_IN_BATCH_MODE =
            Arrays.asList(
                    SOURCE_DATA.stream()
                            .map(x -> Tuple3.of(x, null, Long.MIN_VALUE).toString())
                            .sorted()
                            .collect(joining("+")),
                    END_OF_INPUT_STR);

    static final Queue<String> COMMIT_QUEUE = new ConcurrentLinkedQueue<>();

    static final Queue<String> GLOBAL_COMMIT_QUEUE = new ConcurrentLinkedQueue<>();

    static final BooleanSupplier COMMIT_QUEUE_RECEIVE_ALL_DATA =
            (BooleanSupplier & Serializable)
                    () -> COMMIT_QUEUE.size() == STREAMING_SOURCE_SEND_ELEMENTS_NUM;

    static final BooleanSupplier GLOBAL_COMMIT_QUEUE_RECEIVE_ALL_DATA =
            (BooleanSupplier & Serializable)
                    () ->
                            getSplittedGlobalCommittedData().size()
                                    == STREAMING_SOURCE_SEND_ELEMENTS_NUM;

    static final BooleanSupplier BOTH_QUEUE_RECEIVE_ALL_DATA =
            (BooleanSupplier & Serializable)
                    () ->
                            COMMIT_QUEUE_RECEIVE_ALL_DATA.getAsBoolean()
                                    && GLOBAL_COMMIT_QUEUE_RECEIVE_ALL_DATA.getAsBoolean();

    @Before
    public void init() {
        COMMIT_QUEUE.clear();
        GLOBAL_COMMIT_QUEUE.clear();
    }

    @Test
    public void writerAndCommitterAndGlobalCommitterExecuteInStreamingMode() throws Exception {
        final StreamExecutionEnvironment env = buildStreamEnv();
        final FiniteTestSource<Integer> source =
                new FiniteTestSource<>(BOTH_QUEUE_RECEIVE_ALL_DATA, SOURCE_DATA);

        env.addSource(source, IntegerTypeInfo.INT_TYPE_INFO)
                .sinkTo(
                        TestSink.newBuilder()
                                .setDefaultCommitter(
                                        (Supplier<Queue<String>> & Serializable) () -> COMMIT_QUEUE)
                                .setGlobalCommitter(
                                        (Supplier<Queue<String>> & Serializable)
                                                () -> GLOBAL_COMMIT_QUEUE)
                                .build());

        env.execute();

        // TODO: At present, for a bounded scenario, the occurrence of final checkpoint is not a
        // deterministic event, so
        // we do not need to verify this matter. After the final checkpoint becomes ready in the
        // future,
        // the verification of "end of input" would be restored.
        GLOBAL_COMMIT_QUEUE.remove(END_OF_INPUT_STR);

        assertThat(
                COMMIT_QUEUE,
                containsInAnyOrder(EXPECTED_COMMITTED_DATA_IN_STREAMING_MODE.toArray()));

        assertThat(
                getSplittedGlobalCommittedData(),
                containsInAnyOrder(EXPECTED_GLOBAL_COMMITTED_DATA_IN_STREAMING_MODE.toArray()));
    }

    @Test
    public void writerAndCommitterAndGlobalCommitterExecuteInBatchMode() throws Exception {
        final StreamExecutionEnvironment env = buildBatchEnv();

        env.fromCollection(SOURCE_DATA)
                .sinkTo(
                        TestSink.newBuilder()
                                .setDefaultCommitter(
                                        (Supplier<Queue<String>> & Serializable) () -> COMMIT_QUEUE)
                                .setGlobalCommitter(
                                        (Supplier<Queue<String>> & Serializable)
                                                () -> GLOBAL_COMMIT_QUEUE)
                                .build());

        env.execute();

        assertThat(
                COMMIT_QUEUE, containsInAnyOrder(EXPECTED_COMMITTED_DATA_IN_BATCH_MODE.toArray()));

        assertThat(
                GLOBAL_COMMIT_QUEUE,
                containsInAnyOrder(EXPECTED_GLOBAL_COMMITTED_DATA_IN_BATCH_MODE.toArray()));
    }

    @Test
    public void writerAndCommitterExecuteInStreamingMode() throws Exception {
        final StreamExecutionEnvironment env = buildStreamEnv();
        final FiniteTestSource<Integer> source =
                new FiniteTestSource<>(COMMIT_QUEUE_RECEIVE_ALL_DATA, SOURCE_DATA);

        env.addSource(source, IntegerTypeInfo.INT_TYPE_INFO)
                .sinkTo(
                        TestSink.newBuilder()
                                .setDefaultCommitter(
                                        (Supplier<Queue<String>> & Serializable) () -> COMMIT_QUEUE)
                                .build());
        env.execute();
        assertThat(
                COMMIT_QUEUE,
                containsInAnyOrder(EXPECTED_COMMITTED_DATA_IN_STREAMING_MODE.toArray()));
    }

    @Test
    public void writerAndCommitterExecuteInBatchMode() throws Exception {
        final StreamExecutionEnvironment env = buildBatchEnv();

        env.fromCollection(SOURCE_DATA)
                .sinkTo(
                        TestSink.newBuilder()
                                .setDefaultCommitter(
                                        (Supplier<Queue<String>> & Serializable) () -> COMMIT_QUEUE)
                                .build());
        env.execute();
        assertThat(
                COMMIT_QUEUE, containsInAnyOrder(EXPECTED_COMMITTED_DATA_IN_BATCH_MODE.toArray()));
    }

    @Test
    public void writerAndGlobalCommitterExecuteInStreamingMode() throws Exception {
        final StreamExecutionEnvironment env = buildStreamEnv();
        final FiniteTestSource<Integer> source =
                new FiniteTestSource<>(GLOBAL_COMMIT_QUEUE_RECEIVE_ALL_DATA, SOURCE_DATA);

        env.addSource(source, IntegerTypeInfo.INT_TYPE_INFO)
                .sinkTo(
                        TestSink.newBuilder()
                                .setCommittableSerializer(
                                        TestSink.StringCommittableSerializer.INSTANCE)
                                .setGlobalCommitter(
                                        (Supplier<Queue<String>> & Serializable)
                                                () -> GLOBAL_COMMIT_QUEUE)
                                .build());

        env.execute();

        // TODO: At present, for a bounded scenario, the occurrence of final checkpoint is not a
        // deterministic event, so
        // we do not need to verify this matter. After the final checkpoint becomes ready in the
        // future,
        // the verification of "end of input" would be restored.
        GLOBAL_COMMIT_QUEUE.remove(END_OF_INPUT_STR);

        assertThat(
                getSplittedGlobalCommittedData(),
                containsInAnyOrder(EXPECTED_GLOBAL_COMMITTED_DATA_IN_STREAMING_MODE.toArray()));
    }

    @Test
    public void writerAndGlobalCommitterExecuteInBatchMode() throws Exception {
        final StreamExecutionEnvironment env = buildBatchEnv();

        env.fromCollection(SOURCE_DATA)
                .sinkTo(
                        TestSink.newBuilder()
                                .setCommittableSerializer(
                                        TestSink.StringCommittableSerializer.INSTANCE)
                                .setGlobalCommitter(
                                        (Supplier<Queue<String>> & Serializable)
                                                () -> GLOBAL_COMMIT_QUEUE)
                                .build());
        env.execute();

        assertThat(
                GLOBAL_COMMIT_QUEUE,
                containsInAnyOrder(EXPECTED_GLOBAL_COMMITTED_DATA_IN_BATCH_MODE.toArray()));
    }

    @Test
    public void testMetrics() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        int numSplits = Math.max(1, env.getParallelism() - 2);

        int numRecordsPerSplit = 10;

        // make sure all parallel instances have processed the same amount of records before
        // validating metrics
        SharedReference<CyclicBarrier> beforeBarrier =
                sharedObjects.add(new CyclicBarrier(numSplits + 1));
        SharedReference<CyclicBarrier> afterBarrier =
                sharedObjects.add(new CyclicBarrier(numSplits + 1));
        int stopAtRecord1 = 4;
        int stopAtRecord2 = numRecordsPerSplit - 1;

        env.fromSequence(0, numSplits - 1)
                .<Long>flatMap(
                        (split, collector) ->
                                LongStream.range(0, numRecordsPerSplit).forEach(collector::collect))
                .returns(BasicTypeInfo.LONG_TYPE_INFO)
                .map(
                        i -> {
                            if (i % numRecordsPerSplit == stopAtRecord1
                                    || i % numRecordsPerSplit == stopAtRecord2) {
                                beforeBarrier.get().await();
                                afterBarrier.get().await();
                            }
                            return i;
                        })
                .sinkTo(TestSink.newBuilder().setWriter(new MetricWriter()).build())
                .name("TestSink");
        JobClient jobClient = env.executeAsync();

        beforeBarrier.get().await();
        assertSinkMetrics(stopAtRecord1, env.getParallelism(), numSplits);
        afterBarrier.get().await();

        beforeBarrier.get().await();
        assertSinkMetrics(stopAtRecord2, env.getParallelism(), numSplits);
        afterBarrier.get().await();

        jobClient.getJobExecutionResult().get();
    }

    private void assertSinkMetrics(
            long processedRecordsPerSubtask, int parallelism, int numSplits) {
        List<OperatorMetricGroup> groups =
                inMemoryReporter.getReporter().findOperatorMetricGroups("TestSink");
        //        assertThat(groups, hasSize(parallelism));
        LOG.info(
                "groups: {}",
                groups.stream()
                        .map(g -> Arrays.toString(g.getScopeComponents()))
                        .collect(Collectors.toList()));

        int subtaskWithMetrics = 0;
        for (OperatorMetricGroup group : groups) {
            Map<String, Metric> metrics = inMemoryReporter.getReporter().getMetricsByGroup(group);
            // there are only 2 splits assigned; so two groups will not update metrics
            if (group.getIOMetricGroup().getNumRecordsOutCounter().getCount() == 0) {
                continue;
            }
            subtaskWithMetrics++;
            // I/O metrics
            assertThat(
                    group.getIOMetricGroup().getNumRecordsOutCounter(),
                    isCounter(equalTo(processedRecordsPerSubtask)));
            assertThat(
                    group.getIOMetricGroup().getNumBytesOutCounter(),
                    isCounter(
                            equalTo(
                                    processedRecordsPerSubtask
                                            * MetricWriter.RECORD_SIZE_IN_BYTES)));
            // MetricWriter is just incrementing errors every even record
            assertThat(
                    metrics.get(MetricNames.NUM_RECORDS_OUT_ERRORS),
                    isCounter(equalTo((processedRecordsPerSubtask + 1) / 2)));
            // check if the latest send time is fetched
            assertThat(
                    metrics.get(MetricNames.CURRENT_SEND_TIME),
                    isGauge(
                            equalTo(
                                    (processedRecordsPerSubtask - 1)
                                            * MetricWriter.BASE_SEND_TIME)));
        }
        assertThat(subtaskWithMetrics, equalTo(numSplits));
    }

    private static List<String> getSplittedGlobalCommittedData() {
        return GLOBAL_COMMIT_QUEUE.stream()
                .flatMap(x -> Arrays.stream(x.split("\\+")))
                .collect(Collectors.toList());
    }

    private StreamExecutionEnvironment buildStreamEnv() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        env.enableCheckpointing(100);
        return env;
    }

    private StreamExecutionEnvironment buildBatchEnv() {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        return env;
    }

    private static class MetricWriter extends TestSink.DefaultSinkWriter<Long> {
        static final long BASE_SEND_TIME = 100;
        static final long RECORD_SIZE_IN_BYTES = 10;
        private SinkWriterMetricGroup metricGroup;
        private long sendTime;

        @Override
        public void init(Sink.InitContext context) {
            this.metricGroup = context.metricGroup();
            metricGroup.setCurrentSendTimeGauge(() -> sendTime);
        }

        @Override
        public void write(Long element, Context context) {
            super.write(element, context);
            sendTime = element * BASE_SEND_TIME;
            if (element % 2 == 0) {
                metricGroup.getNumRecordsOutErrorsCounter().inc();
            }
            metricGroup.getIOMetricGroup().getNumBytesOutCounter().inc(RECORD_SIZE_IN_BYTES);
        }
    }
}

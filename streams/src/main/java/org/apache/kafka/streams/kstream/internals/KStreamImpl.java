/**
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

package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValue;
import org.apache.kafka.streams.kstream.Reducer;
import org.apache.kafka.streams.kstream.TransformerSupplier;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.ValueTransformerSupplier;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.streams.kstream.Window;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.Windows;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.StreamPartitioner;
import org.apache.kafka.streams.state.internals.RocksDBWindowStoreSupplier;
import org.apache.kafka.streams.state.Serdes;

import java.lang.reflect.Array;
import java.util.HashSet;
import java.util.Set;

public class KStreamImpl<K, V> extends AbstractStream<K> implements KStream<K, V> {

    private static final String AGGREGATE_NAME = "KSTREAM-AGGREGATE-";

    private static final String BRANCH_NAME = "KSTREAM-BRANCH-";

    private static final String BRANCHCHILD_NAME = "KSTREAM-BRANCHCHILD-";

    private static final String FILTER_NAME = "KSTREAM-FILTER-";

    private static final String FLATMAP_NAME = "KSTREAM-FLATMAP-";

    private static final String FLATMAPVALUES_NAME = "KSTREAM-FLATMAPVALUES-";

    public static final String JOINTHIS_NAME = "KSTREAM-JOINTHIS-";

    public static final String JOINOTHER_NAME = "KSTREAM-JOINOTHER-";

    public static final String LEFTJOIN_NAME = "KSTREAM-LEFTJOIN-";

    private static final String MAP_NAME = "KSTREAM-MAP-";

    private static final String MAPVALUES_NAME = "KSTREAM-MAPVALUES-";

    public static final String MERGE_NAME = "KSTREAM-MERGE-";

    public static final String OUTERTHIS_NAME = "KSTREAM-OUTERTHIS-";

    public static final String OUTEROTHER_NAME = "KSTREAM-OUTEROTHER-";

    private static final String PROCESSOR_NAME = "KSTREAM-PROCESSOR-";

    private static final String REDUCE_NAME = "KSTREAM-REDUCE-";

    private static final String SELECT_NAME = "KSTREAM-SELECT-";

    public static final String SINK_NAME = "KSTREAM-SINK-";

    public static final String SOURCE_NAME = "KSTREAM-SOURCE-";

    private static final String TRANSFORM_NAME = "KSTREAM-TRANSFORM-";

    private static final String TRANSFORMVALUES_NAME = "KSTREAM-TRANSFORMVALUES-";

    private static final String WINDOWED_NAME = "KSTREAM-WINDOWED-";


    public KStreamImpl(KStreamBuilder topology, String name, Set<String> sourceNodes) {
        super(topology, name, sourceNodes);
    }

    @Override
    public KStream<K, V> filter(Predicate<K, V> predicate) {
        String name = topology.newName(FILTER_NAME);

        topology.addProcessor(name, new KStreamFilter<>(predicate, false), this.name);

        return new KStreamImpl<>(topology, name, sourceNodes);
    }

    @Override
    public KStream<K, V> filterOut(final Predicate<K, V> predicate) {
        String name = topology.newName(FILTER_NAME);

        topology.addProcessor(name, new KStreamFilter<>(predicate, true), this.name);

        return new KStreamImpl<>(topology, name, sourceNodes);
    }

    @Override
    public <K1, V1> KStream<K1, V1> map(KeyValueMapper<K, V, KeyValue<K1, V1>> mapper) {
        String name = topology.newName(MAP_NAME);

        topology.addProcessor(name, new KStreamMap<>(mapper), this.name);

        return new KStreamImpl<>(topology, name, null);
    }

    @Override
    public <V1> KStream<K, V1> mapValues(ValueMapper<V, V1> mapper) {
        String name = topology.newName(MAPVALUES_NAME);

        topology.addProcessor(name, new KStreamMapValues<>(mapper), this.name);

        return new KStreamImpl<>(topology, name, sourceNodes);
    }

    @Override
    public <K1, V1> KStream<K1, V1> flatMap(KeyValueMapper<K, V, Iterable<KeyValue<K1, V1>>> mapper) {
        String name = topology.newName(FLATMAP_NAME);

        topology.addProcessor(name, new KStreamFlatMap<>(mapper), this.name);

        return new KStreamImpl<>(topology, name, null);
    }

    @Override
    public <V1> KStream<K, V1> flatMapValues(ValueMapper<V, Iterable<V1>> mapper) {
        String name = topology.newName(FLATMAPVALUES_NAME);

        topology.addProcessor(name, new KStreamFlatMapValues<>(mapper), this.name);

        return new KStreamImpl<>(topology, name, sourceNodes);
    }

    @Override
    @SuppressWarnings("unchecked")
    public KStream<K, V>[] branch(Predicate<K, V>... predicates) {
        String branchName = topology.newName(BRANCH_NAME);

        topology.addProcessor(branchName, new KStreamBranch(predicates.clone()), this.name);

        KStream<K, V>[] branchChildren = (KStream<K, V>[]) Array.newInstance(KStream.class, predicates.length);
        for (int i = 0; i < predicates.length; i++) {
            String childName = topology.newName(BRANCHCHILD_NAME);

            topology.addProcessor(childName, new KStreamPassThrough<K, V>(), branchName);

            branchChildren[i] = new KStreamImpl<>(topology, childName, sourceNodes);
        }

        return branchChildren;
    }

    public static <K, V> KStream<K, V> merge(KStreamBuilder topology, KStream<K, V>[] streams) {
        String name = topology.newName(MERGE_NAME);
        String[] parentNames = new String[streams.length];
        Set<String> allSourceNodes = new HashSet<>();

        for (int i = 0; i < streams.length; i++) {
            KStreamImpl stream = (KStreamImpl) streams[i];

            parentNames[i] = stream.name;

            if (allSourceNodes != null) {
                if (stream.sourceNodes != null)
                    allSourceNodes.addAll(stream.sourceNodes);
                else
                    allSourceNodes = null;
            }

        }

        topology.addProcessor(name, new KStreamPassThrough<>(), parentNames);

        return new KStreamImpl<>(topology, name, allSourceNodes);
    }

    @Override
    public KStream<K, V> through(String topic,
                                 Serializer<K> keySerializer,
                                 Serializer<V> valSerializer,
                                 Deserializer<K> keyDeserializer,
                                 Deserializer<V> valDeserializer) {
        to(topic, keySerializer, valSerializer);

        return topology.stream(keyDeserializer, valDeserializer, topic);
    }

    @Override
    public KStream<K, V> through(String topic) {
        return through(topic, null, null, null, null);
    }

    @Override
    public void to(String topic) {
        to(topic, null, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void to(String topic, Serializer<K> keySerializer, Serializer<V> valSerializer) {
        String name = topology.newName(SINK_NAME);
        StreamPartitioner<K, V> streamPartitioner = null;

        if (keySerializer != null && keySerializer instanceof WindowedSerializer) {
            WindowedSerializer<Object> windowedSerializer = (WindowedSerializer<Object>) keySerializer;
            streamPartitioner = (StreamPartitioner<K, V>) new WindowedStreamPartitioner<Object, V>(windowedSerializer);
        }

        topology.addSink(name, topic, keySerializer, valSerializer, streamPartitioner, this.name);
    }

    @Override
    public <K1, V1> KStream<K1, V1> transform(TransformerSupplier<K, V, KeyValue<K1, V1>> transformerSupplier, String... stateStoreNames) {
        String name = topology.newName(TRANSFORM_NAME);

        topology.addProcessor(name, new KStreamTransform<>(transformerSupplier), this.name);
        topology.connectProcessorAndStateStores(name, stateStoreNames);

        return new KStreamImpl<>(topology, name, null);
    }

    @Override
    public <V1> KStream<K, V1> transformValues(ValueTransformerSupplier<V, V1> valueTransformerSupplier, String... stateStoreNames) {
        String name = topology.newName(TRANSFORMVALUES_NAME);

        topology.addProcessor(name, new KStreamTransformValues<>(valueTransformerSupplier), this.name);
        topology.connectProcessorAndStateStores(name, stateStoreNames);

        return new KStreamImpl<>(topology, name, sourceNodes);
    }

    @Override
    public void process(final ProcessorSupplier<K, V> processorSupplier, String... stateStoreNames) {
        String name = topology.newName(PROCESSOR_NAME);

        topology.addProcessor(name, processorSupplier, this.name);
        topology.connectProcessorAndStateStores(name, stateStoreNames);
    }

    @Override
    public <V1, R> KStream<K, R> join(
            KStream<K, V1> other,
            ValueJoiner<V, V1, R> joiner,
            JoinWindows windows,
            Serializer<K> keySerializer,
            Serializer<V> thisValueSerializer,
            Serializer<V1> otherValueSerializer,
            Deserializer<K> keyDeserializer,
            Deserializer<V> thisValueDeserializer,
            Deserializer<V1> otherValueDeserializer) {

        return join(other, joiner, windows,
                keySerializer, thisValueSerializer, otherValueSerializer,
                keyDeserializer, thisValueDeserializer, otherValueDeserializer, false);
    }

    @Override
    public <V1, R> KStream<K, R> outerJoin(
            KStream<K, V1> other,
            ValueJoiner<V, V1, R> joiner,
            JoinWindows windows,
            Serializer<K> keySerializer,
            Serializer<V> thisValueSerializer,
            Serializer<V1> otherValueSerializer,
            Deserializer<K> keyDeserializer,
            Deserializer<V> thisValueDeserializer,
            Deserializer<V1> otherValueDeserializer) {

        return join(other, joiner, windows,
                keySerializer, thisValueSerializer, otherValueSerializer,
                keyDeserializer, thisValueDeserializer, otherValueDeserializer, true);
    }

    @SuppressWarnings("unchecked")
    private <V1, R> KStream<K, R> join(
            KStream<K, V1> other,
            ValueJoiner<V, V1, R> joiner,
            JoinWindows windows,
            Serializer<K> keySerializer,
            Serializer<V> thisValueSerializer,
            Serializer<V1> otherValueSerializer,
            Deserializer<K> keyDeserializer,
            Deserializer<V> thisValueDeserializer,
            Deserializer<V1> otherValueDeserializer,
            boolean outer) {

        Set<String> allSourceNodes = ensureJoinableWith((AbstractStream<K>) other);

        RocksDBWindowStoreSupplier<K, V> thisWindow =
                new RocksDBWindowStoreSupplier<>(
                        windows.name() + "-this",
                        windows.maintainMs(),
                        windows.segments,
                        true,
                        new Serdes<>("", keySerializer, keyDeserializer, thisValueSerializer, thisValueDeserializer),
                        null);

        RocksDBWindowStoreSupplier<K, V1> otherWindow =
                new RocksDBWindowStoreSupplier<>(
                        windows.name() + "-other",
                        windows.maintainMs(),
                        windows.segments,
                        true,
                        new Serdes<>("", keySerializer, keyDeserializer, otherValueSerializer, otherValueDeserializer),
                        null);

        KStreamJoinWindow<K, V> thisWindowedStream = new KStreamJoinWindow<>(thisWindow.name(), windows.before + windows.after + 1, windows.maintainMs());
        KStreamJoinWindow<K, V1> otherWindowedStream = new KStreamJoinWindow<>(otherWindow.name(), windows.before + windows.after + 1, windows.maintainMs());

        KStreamKStreamJoin<K, R, V, V1> joinThis = new KStreamKStreamJoin<>(otherWindow.name(), windows.before, windows.after, joiner, outer);
        KStreamKStreamJoin<K, R, V1, V> joinOther = new KStreamKStreamJoin<>(thisWindow.name(), windows.before, windows.after, reverseJoiner(joiner), outer);

        KStreamPassThrough<K, R> joinMerge = new KStreamPassThrough<>();

        String thisWindowStreamName = topology.newName(WINDOWED_NAME);
        String otherWindowStreamName = topology.newName(WINDOWED_NAME);
        String joinThisName = outer ? topology.newName(OUTERTHIS_NAME) : topology.newName(JOINTHIS_NAME);
        String joinOtherName = outer ? topology.newName(OUTEROTHER_NAME) : topology.newName(JOINOTHER_NAME);
        String joinMergeName = topology.newName(MERGE_NAME);

        topology.addProcessor(thisWindowStreamName, thisWindowedStream, this.name);
        topology.addProcessor(otherWindowStreamName, otherWindowedStream, ((KStreamImpl) other).name);
        topology.addProcessor(joinThisName, joinThis, thisWindowStreamName);
        topology.addProcessor(joinOtherName, joinOther, otherWindowStreamName);
        topology.addProcessor(joinMergeName, joinMerge, joinThisName, joinOtherName);
        topology.addStateStore(thisWindow, thisWindowStreamName, otherWindowStreamName);
        topology.addStateStore(otherWindow, thisWindowStreamName, otherWindowStreamName);

        return new KStreamImpl<>(topology, joinMergeName, allSourceNodes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V1, R> KStream<K, R> leftJoin(
            KStream<K, V1> other,
            ValueJoiner<V, V1, R> joiner,
            JoinWindows windows,
            Serializer<K> keySerializer,
            Serializer<V1> otherValueSerializer,
            Deserializer<K> keyDeserializer,
            Deserializer<V1> otherValueDeserializer) {

        Set<String> allSourceNodes = ensureJoinableWith((AbstractStream<K>) other);

        RocksDBWindowStoreSupplier<K, V1> otherWindow =
                new RocksDBWindowStoreSupplier<>(
                        windows.name() + "-this",
                        windows.maintainMs(),
                        windows.segments,
                        true,
                        new Serdes<>("", keySerializer, keyDeserializer, otherValueSerializer, otherValueDeserializer),
                        null);

        KStreamJoinWindow<K, V1> otherWindowedStream = new KStreamJoinWindow<>(otherWindow.name(), windows.before + windows.after + 1, windows.maintainMs());
        KStreamKStreamJoin<K, R, V, V1> joinThis = new KStreamKStreamJoin<>(otherWindow.name(), windows.before, windows.after, joiner, true);

        String otherWindowStreamName = topology.newName(WINDOWED_NAME);
        String joinThisName = topology.newName(LEFTJOIN_NAME);

        topology.addProcessor(otherWindowStreamName, otherWindowedStream, ((KStreamImpl) other).name);
        topology.addProcessor(joinThisName, joinThis, this.name);
        topology.addStateStore(otherWindow, joinThisName, otherWindowStreamName);

        return new KStreamImpl<>(topology, joinThisName, allSourceNodes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V1, R> KStream<K, R> leftJoin(KTable<K, V1> other, ValueJoiner<V, V1, R> joiner) {
        Set<String> allSourceNodes = ensureJoinableWith((AbstractStream<K>) other);

        String name = topology.newName(LEFTJOIN_NAME);

        topology.addProcessor(name, new KStreamKTableLeftJoin<>((KTableImpl<K, ?, V1>) other, joiner), this.name);

        return new KStreamImpl<>(topology, name, allSourceNodes);
    }

    @Override
    public <W extends Window> KTable<Windowed<K>, V> reduceByKey(Reducer<V> reducer,
                                                                 Windows<W> windows,
                                                                 Serializer<K> keySerializer,
                                                                 Serializer<V> aggValueSerializer,
                                                                 Deserializer<K> keyDeserializer,
                                                                 Deserializer<V> aggValueDeserializer) {

        // TODO: this agg window operator is only used for casting K to Windowed<K> for
        // KTableProcessorSupplier, which is a bit awkward and better be removed in the future
        String reduceName = topology.newName(REDUCE_NAME);
        String selectName = topology.newName(SELECT_NAME);

        ProcessorSupplier<K, V> aggWindowSupplier = new KStreamAggWindow<>();
        ProcessorSupplier<Windowed<K>, Change<V>> aggregateSupplier = new KStreamReduce<>(windows, windows.name(), reducer);

        RocksDBWindowStoreSupplier<K, V> aggregateStore =
                new RocksDBWindowStoreSupplier<>(
                        windows.name(),
                        windows.maintainMs(),
                        windows.segments,
                        false,
                        new Serdes<>("", keySerializer, keyDeserializer, aggValueSerializer, aggValueDeserializer),
                        null);

        // aggregate the values with the aggregator and local store
        topology.addProcessor(selectName, aggWindowSupplier, this.name);
        topology.addProcessor(reduceName, aggregateSupplier, selectName);
        topology.addStateStore(aggregateStore, reduceName);

        // return the KTable representation with the intermediate topic as the sources
        return new KTableImpl<>(topology, reduceName, aggregateSupplier, sourceNodes);
    }

    @Override
    public <T, W extends Window> KTable<Windowed<K>, T> aggregateByKey(Aggregator<K, V, T> aggregator,
                                                                       Windows<W> windows,
                                                                       Serializer<K> keySerializer,
                                                                       Serializer<T> aggValueSerializer,
                                                                       Deserializer<K> keyDeserializer,
                                                                       Deserializer<T> aggValueDeserializer) {

        // TODO: this agg window operator is only used for casting K to Windowed<K> for
        // KTableProcessorSupplier, which is a bit awkward and better be removed in the future
        String aggregateName = topology.newName(AGGREGATE_NAME);
        String selectName = topology.newName(SELECT_NAME);

        ProcessorSupplier<K, V> aggWindowSupplier = new KStreamAggWindow<>();
        ProcessorSupplier<Windowed<K>, Change<V>> aggregateSupplier = new KStreamAggregate<>(windows, windows.name(), aggregator);

        RocksDBWindowStoreSupplier<K, T> aggregateStore =
                new RocksDBWindowStoreSupplier<>(
                        windows.name(),
                        windows.maintainMs(),
                        windows.segments,
                        false,
                        new Serdes<>("", keySerializer, keyDeserializer, aggValueSerializer, aggValueDeserializer),
                        null);

        // aggregate the values with the aggregator and local store
        topology.addProcessor(selectName, aggWindowSupplier, this.name);
        topology.addProcessor(aggregateName, aggregateSupplier, selectName);
        topology.addStateStore(aggregateStore, aggregateName);

        // return the KTable representation with the intermediate topic as the sources
        return new KTableImpl<>(topology, aggregateName, aggregateSupplier, sourceNodes);
    }
}

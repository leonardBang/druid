/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.groupby;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.metamx.common.ISE;
import com.metamx.common.Pair;
import com.metamx.common.guava.Accumulator;
import io.druid.collections.StupidPool;
import io.druid.data.input.MapBasedInputRow;
import io.druid.data.input.MapBasedRow;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.dimension.DimensionSpec;
import io.druid.segment.incremental.IncrementalIndex;
import io.druid.segment.incremental.IndexSizeExceededException;
import io.druid.segment.incremental.OnheapIncrementalIndex;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class GroupByQueryHelper
{
  public static <T> Pair<IncrementalIndex, Accumulator<IncrementalIndex, T>> createIndexAccumulatorPair(
      final GroupByQuery query,
      final GroupByQueryConfig config,
      StupidPool<ByteBuffer> bufferPool

  )
  {
    final QueryGranularity gran = query.getGranularity();
    final long timeStart = query.getIntervals().get(0).getStartMillis();

    // use gran.iterable instead of gran.truncate so that
    // AllGranularity returns timeStart instead of Long.MIN_VALUE
    final long granTimeStart = gran.iterable(timeStart, timeStart + 1).iterator().next();

    final List<AggregatorFactory> aggs = Lists.transform(
        query.getAggregatorSpecs(),
        new Function<AggregatorFactory, AggregatorFactory>()
        {
          @Override
          public AggregatorFactory apply(AggregatorFactory input)
          {
            return input.getCombiningFactory();
          }
        }
    );
    final List<String> dimensions = Lists.transform(
        query.getDimensions(),
        new Function<DimensionSpec, String>()
        {
          @Override
          public String apply(DimensionSpec input)
          {
            return input.getOutputName();
          }
        }
    );
    final IncrementalIndex index = new OnheapIncrementalIndex(
        // use granularity truncated min timestamp
        // since incoming truncated timestamps may precede timeStart
        granTimeStart,
        gran,
        aggs.toArray(new AggregatorFactory[aggs.size()]),
        false,
        config.getMaxResults()
    );

    Accumulator<IncrementalIndex, T> accumulator = new Accumulator<IncrementalIndex, T>()
    {
      @Override
      public IncrementalIndex accumulate(IncrementalIndex accumulated, T in)
      {

        if (in instanceof MapBasedRow) {
          try {
            MapBasedRow row = (MapBasedRow) in;
            accumulated.add(
                new MapBasedInputRow(
                    row.getTimestamp(),
                    dimensions,
                    row.getEvent()
                )
            );
          }
          catch (IndexSizeExceededException e) {
            throw new ISE(e.getMessage());
          }
        } else {
          throw new ISE("Unable to accumulate something of type [%s]", in.getClass());
        }

        return accumulated;
      }
    };
    return new Pair<>(index, accumulator);
  }

  public static <T> Pair<Queue, Accumulator<Queue, T>> createBySegmentAccumulatorPair()
  {
    // In parallel query runner multiple threads add to this queue concurrently
    Queue init = new ConcurrentLinkedQueue<>();
    Accumulator<Queue, T> accumulator = new Accumulator<Queue, T>()
    {
      @Override
      public Queue accumulate(Queue accumulated, T in)
      {
        if (in == null) {
          throw new ISE("Cannot have null result");
        }
        accumulated.offer(in);
        return accumulated;
      }
    };
    return new Pair<>(init, accumulator);
  }
}

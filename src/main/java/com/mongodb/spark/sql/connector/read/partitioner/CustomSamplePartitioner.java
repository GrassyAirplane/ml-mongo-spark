/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.mongodb.spark.sql.connector.read.partitioner;

import static com.mongodb.spark.sql.connector.read.partitioner.PartitionerHelper.SINGLE_PARTITIONER;
import static java.lang.String.format;
import static java.util.Arrays.asList;

import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.spark.sql.connector.assertions.Assertions;
import com.mongodb.spark.sql.connector.config.MongoConfig;
import com.mongodb.spark.sql.connector.config.ReadConfig;
import com.mongodb.spark.sql.connector.read.MongoInputPartition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Custom Sample Partitioner
 *
 * <p>Creates partitions based on sampling the collection, without requiring collection stats.
 * This partitioner is optimized for performance compared to PaginateIntoPartitionsPartitioner.
 *
 * <p>The partitioner takes a fixed number of samples from the collection based on the desired number of partitions
 * and uses these samples to create partition boundaries.
 *
 * <ul>
 *   <li>{@value PARTITION_FIELD_CONFIG}: The field to be used for partitioning. Must be a unique
 *       field. Defaults to: {@value ID_FIELD}.
 *   <li>{@value NUM_PARTITIONS_CONFIG}: The desired number of partitions to create.
 *       Defaults to: {@value NUM_PARTITIONS_DEFAULT}.
 *   <li>{@value SAMPLES_MULTIPLIER_CONFIG}: Multiplier for the number of samples to take.
 *       Total samples = numPartitions * samplesMultiplier.
 *       Defaults to: {@value SAMPLES_MULTIPLIER_DEFAULT}.
 * </ul>
 */
@ApiStatus.Internal
public final class CustomSamplePartitioner extends FieldPartitioner {
  public static final String NUM_PARTITIONS_CONFIG = "max.number.of.partitions";
  private static final int NUM_PARTITIONS_DEFAULT = 1;

  public static final String SAMPLES_MULTIPLIER_CONFIG = "samples.multiplier";
  private static final int SAMPLES_MULTIPLIER_DEFAULT = 10;

  /** Construct an instance */
  public CustomSamplePartitioner() {}

  @Override
  public List<MongoInputPartition> generatePartitions(final ReadConfig readConfig) {
    MongoConfig partitionerOptions = readConfig.getPartitionerOptions();
    String partitionField = getPartitionField(readConfig);

    int numPartitions = Assertions.validateConfig(
        partitionerOptions.getInt(NUM_PARTITIONS_CONFIG, NUM_PARTITIONS_DEFAULT),
        i -> i > 0,
        () -> format("Invalid config: %s should be greater than zero.", NUM_PARTITIONS_CONFIG));

    int samplesMultiplier = Assertions.validateConfig(
        partitionerOptions.getInt(SAMPLES_MULTIPLIER_CONFIG, SAMPLES_MULTIPLIER_DEFAULT),
        i -> i > 0,
        () -> format("Invalid config: %s should be greater than zero.", SAMPLES_MULTIPLIER_CONFIG));

    if (numPartitions <= 1) {
      LOGGER.info("Number of partitions is {}. Returning a single partition", numPartitions);
      return SINGLE_PARTITIONER.generatePartitions(readConfig);
    }

    // Calculate the number of samples to take
    int totalSamples = numPartitions * samplesMultiplier;

    // Sample the collection
    BsonDocument matchQuery = PartitionerHelper.matchQuery(readConfig.getAggregationPipeline());
    Bson projection = partitionField.equals(ID_FIELD)
        ? Projections.include(partitionField)
        : Projections.fields(Projections.include(partitionField), Projections.excludeId());

    LOGGER.info("Sampling {} documents with field: {}", totalSamples, partitionField);

    List<BsonDocument> samples = readConfig.withCollection(coll -> coll.aggregate(asList(
            Aggregates.match(matchQuery),
            Aggregates.sample(totalSamples),
            Aggregates.project(projection),
            Aggregates.sort(Sorts.ascending(partitionField))))
        .allowDiskUse(readConfig.getAggregationAllowDiskUse())
        .comment(readConfig.getComment())
        .into(new ArrayList<>()));

    if (samples.isEmpty()) {
      LOGGER.info("No samples found. Returning a single partition");
      return SINGLE_PARTITIONER.generatePartitions(readConfig);
    }

    // Extract right-hand boundaries for partitions
    List<BsonDocument> boundaries = extractBoundaries(samples, samplesMultiplier);

    LOGGER.info("Generated {} partition boundaries", boundaries.size());

    // Create partitions
    return createMongoInputPartitions(partitionField, boundaries, readConfig);
  }

  /**
   * Extracts boundary documents from the samples to create partition boundaries.
   * Takes every samplesMultiplier'th document as a boundary.
   */
  @NotNull
  private List<BsonDocument> extractBoundaries(
      final List<BsonDocument> samples, final int samplesMultiplier) {
    if (samples.size() <= samplesMultiplier) {
      // If we have fewer samples than expected, just return the last sample
      if (samples.size() > 1) {
        ArrayList<BsonDocument> result = new ArrayList<>();
        result.add(samples.get(samples.size() - 1));
        return result;
      } else {
        return Collections.emptyList();
      }
    }

    // Take every samplesMultiplier'th sample as a boundary, skipping the first one
    // since we only need right-hand boundaries
    return IntStream.range(1, samples.size())
        .filter(i -> i % samplesMultiplier == 0)
        .mapToObj(samples::get)
        .collect(Collectors.toList());
  }
}

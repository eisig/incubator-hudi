/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie.utilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.uber.hoodie.DataSourceWriteOptions;
import com.uber.hoodie.SimpleKeyGenerator;
import com.uber.hoodie.common.model.HoodieCommitMetadata;
import com.uber.hoodie.common.model.HoodieTableType;
import com.uber.hoodie.common.table.HoodieTableMetaClient;
import com.uber.hoodie.common.table.HoodieTimeline;
import com.uber.hoodie.common.table.timeline.HoodieInstant;
import com.uber.hoodie.common.util.DFSPropertiesConfiguration;
import com.uber.hoodie.common.util.TypedProperties;
import com.uber.hoodie.config.HoodieCompactionConfig;
import com.uber.hoodie.exception.DatasetNotFoundException;
import com.uber.hoodie.hive.HiveSyncConfig;
import com.uber.hoodie.hive.HoodieHiveClient;
import com.uber.hoodie.hive.MultiPartKeysValueExtractor;
import com.uber.hoodie.utilities.deltastreamer.HoodieDeltaStreamer;
import com.uber.hoodie.utilities.deltastreamer.HoodieDeltaStreamer.Operation;
import com.uber.hoodie.utilities.schema.FilebasedSchemaProvider;
import com.uber.hoodie.utilities.sources.DistributedTestDataSource;
import com.uber.hoodie.utilities.sources.HoodieIncrSource;
import com.uber.hoodie.utilities.sources.InputBatch;
import com.uber.hoodie.utilities.sources.TestDataSource;
import com.uber.hoodie.utilities.sources.config.TestSourceConfig;
import com.uber.hoodie.utilities.transform.SqlQueryBasedTransformer;
import com.uber.hoodie.utilities.transform.Transformer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF4;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Basic tests against {@link com.uber.hoodie.utilities.deltastreamer.HoodieDeltaStreamer}, by issuing bulk_inserts,
 * upserts, inserts. Check counts at the end.
 */
public class TestHoodieDeltaStreamer extends UtilitiesTestBase {
  private static final String PROPS_FILENAME_TEST_SOURCE = "test-source.properties";
  private static final String PROPS_FILENAME_TEST_INVALID = "test-invalid.properties";
  private static volatile Logger log = LogManager.getLogger(TestHoodieDeltaStreamer.class);

  @BeforeClass
  public static void initClass() throws Exception {
    UtilitiesTestBase.initClass(true);

    // prepare the configs.
    UtilitiesTestBase.Helpers.copyToDFS("delta-streamer-config/base.properties", dfs, dfsBasePath + "/base.properties");
    UtilitiesTestBase.Helpers.copyToDFS("delta-streamer-config/sql-transformer.properties", dfs,
        dfsBasePath + "/sql-transformer.properties");
    UtilitiesTestBase.Helpers.copyToDFS("delta-streamer-config/source.avsc", dfs, dfsBasePath + "/source.avsc");
    UtilitiesTestBase.Helpers.copyToDFS("delta-streamer-config/target.avsc", dfs, dfsBasePath + "/target.avsc");

    TypedProperties props = new TypedProperties();
    props.setProperty("include", "sql-transformer.properties");
    props.setProperty("hoodie.datasource.write.keygenerator.class", TestGenerator.class.getName());
    props.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    props.setProperty("hoodie.datasource.write.partitionpath.field", "not_there");
    props.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", dfsBasePath + "/source.avsc");
    props.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", dfsBasePath + "/target.avsc");
    // Hive Configs
    props.setProperty(DataSourceWriteOptions.HIVE_URL_OPT_KEY(), "jdbc:hive2://127.0.0.1:9999/");
    props.setProperty(DataSourceWriteOptions.HIVE_DATABASE_OPT_KEY(), "testdb1");
    props.setProperty(DataSourceWriteOptions.HIVE_TABLE_OPT_KEY(), "hive_trips");
    props.setProperty(DataSourceWriteOptions.HIVE_ASSUME_DATE_PARTITION_OPT_KEY(), "false");
    props.setProperty(DataSourceWriteOptions.HIVE_PARTITION_FIELDS_OPT_KEY(), "datestr");
    props.setProperty(DataSourceWriteOptions.HIVE_PARTITION_EXTRACTOR_CLASS_OPT_KEY(),
        MultiPartKeysValueExtractor.class.getName());
    UtilitiesTestBase.Helpers.savePropsToDFS(props, dfs, dfsBasePath + "/" + PROPS_FILENAME_TEST_SOURCE);

    // Properties used for the delta-streamer which incrementally pulls from upstream Hudi source table and writes to
    // downstream hudi table
    TypedProperties downstreamProps = new TypedProperties();
    downstreamProps.setProperty("include", "base.properties");
    downstreamProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    downstreamProps.setProperty("hoodie.datasource.write.partitionpath.field", "not_there");

    // Source schema is the target schema of upstream table
    downstreamProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", dfsBasePath + "/target.avsc");
    downstreamProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", dfsBasePath + "/target.avsc");
    UtilitiesTestBase.Helpers.savePropsToDFS(downstreamProps, dfs,
        dfsBasePath + "/test-downstream-source.properties");

    // Properties used for testing invalid key generator
    TypedProperties invalidProps = new TypedProperties();
    invalidProps.setProperty("include", "sql-transformer.properties");
    invalidProps.setProperty("hoodie.datasource.write.keygenerator.class", "invalid");
    invalidProps.setProperty("hoodie.datasource.write.recordkey.field", "_row_key");
    invalidProps.setProperty("hoodie.datasource.write.partitionpath.field", "not_there");
    invalidProps.setProperty("hoodie.deltastreamer.schemaprovider.source.schema.file", dfsBasePath + "/source.avsc");
    invalidProps.setProperty("hoodie.deltastreamer.schemaprovider.target.schema.file", dfsBasePath + "/target.avsc");
    UtilitiesTestBase.Helpers.savePropsToDFS(invalidProps, dfs,
        dfsBasePath + "/" + PROPS_FILENAME_TEST_INVALID);
  }

  @AfterClass
  public static void cleanupClass() throws Exception {
    UtilitiesTestBase.cleanupClass();
  }

  @Before
  public void setup() throws Exception {
    super.setup();
    TestDataSource.initDataGen();
  }

  @After
  public void teardown() throws Exception {
    super.teardown();
    TestDataSource.resetDataGen();
  }

  static class TestHelpers {
    static HoodieDeltaStreamer.Config makeConfig(String basePath, Operation op) {
      return makeConfig(basePath, op, TripsWithDistanceTransformer.class.getName());
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, Operation op, String transformerClassName) {
      return makeConfig(basePath, op, transformerClassName, PROPS_FILENAME_TEST_SOURCE, false);
    }

    static HoodieDeltaStreamer.Config makeConfig(String basePath, Operation op, String transformerClassName,
        String propsFilename, boolean enableHiveSync) {
      HoodieDeltaStreamer.Config cfg = new HoodieDeltaStreamer.Config();
      cfg.targetBasePath = basePath;
      cfg.targetTableName = "hoodie_trips";
      cfg.storageType = "COPY_ON_WRITE";
      cfg.sourceClassName = TestDataSource.class.getName();
      cfg.transformerClassName = transformerClassName;
      cfg.operation = op;
      cfg.enableHiveSync = enableHiveSync;
      cfg.sourceOrderingField = "timestamp";
      cfg.propsFilePath = dfsBasePath + "/" + propsFilename;
      cfg.sourceLimit = 1000;
      cfg.schemaProviderClassName = FilebasedSchemaProvider.class.getName();
      return cfg;
    }

    static HoodieDeltaStreamer.Config makeConfigForHudiIncrSrc(String srcBasePath, String basePath, Operation op,
        boolean addReadLatestOnMissingCkpt) {
      HoodieDeltaStreamer.Config cfg = new HoodieDeltaStreamer.Config();
      cfg.targetBasePath = basePath;
      cfg.targetTableName = "hoodie_trips_copy";
      cfg.storageType = "COPY_ON_WRITE";
      cfg.sourceClassName = HoodieIncrSource.class.getName();
      cfg.operation = op;
      cfg.sourceOrderingField = "timestamp";
      cfg.propsFilePath = dfsBasePath + "/test-downstream-source.properties";
      cfg.sourceLimit = 1000;
      List<String> cfgs = new ArrayList<>();
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.read_latest_on_missing_ckpt=" + addReadLatestOnMissingCkpt);
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.path=" + srcBasePath);
      // No partition
      cfgs.add("hoodie.deltastreamer.source.hoodieincr.partition.fields=datestr");
      cfg.configs = cfgs;
      return cfg;
    }

    static void assertRecordCount(long expected, String datasetPath, SQLContext sqlContext) {
      long recordCount = sqlContext.read().format("com.uber.hoodie").load(datasetPath).count();
      assertEquals(expected, recordCount);
    }

    static List<Row> countsPerCommit(String datasetPath, SQLContext sqlContext) {
      return sqlContext.read().format("com.uber.hoodie").load(datasetPath).groupBy("_hoodie_commit_time").count()
          .sort("_hoodie_commit_time").collectAsList();
    }

    static void assertDistanceCount(long expected, String datasetPath, SQLContext sqlContext) {
      sqlContext.read().format("com.uber.hoodie").load(datasetPath).registerTempTable("tmp_trips");
      long recordCount =
          sqlContext.sparkSession().sql("select * from tmp_trips where haversine_distance is not NULL").count();
      assertEquals(expected, recordCount);
    }

    static void assertDistanceCountWithExactValue(long expected, String datasetPath, SQLContext sqlContext) {
      sqlContext.read().format("com.uber.hoodie").load(datasetPath).registerTempTable("tmp_trips");
      long recordCount =
          sqlContext.sparkSession().sql("select * from tmp_trips where haversine_distance = 1.0").count();
      assertEquals(expected, recordCount);
    }

    static void assertAtleastNCompactionCommits(int minExpected, String datasetPath, FileSystem fs) {
      HoodieTableMetaClient meta = new HoodieTableMetaClient(fs.getConf(), datasetPath);
      HoodieTimeline timeline = meta.getActiveTimeline().getCommitTimeline().filterCompletedInstants();
      log.info("Timeline Instants=" + meta.getActiveTimeline().getInstants().collect(Collectors.toList()));
      int numCompactionCommits = (int)timeline.getInstants().count();
      assertTrue("Got=" + numCompactionCommits + ", exp >=" + minExpected, minExpected <= numCompactionCommits);
    }

    static void assertAtleastNDeltaCommits(int minExpected, String datasetPath, FileSystem fs) {
      HoodieTableMetaClient meta = new HoodieTableMetaClient(fs.getConf(), datasetPath);
      HoodieTimeline timeline = meta.getActiveTimeline().getDeltaCommitTimeline().filterCompletedInstants();
      log.info("Timeline Instants=" + meta.getActiveTimeline().getInstants().collect(Collectors.toList()));
      int numDeltaCommits = (int)timeline.getInstants().count();
      assertTrue("Got=" + numDeltaCommits + ", exp >=" + minExpected, minExpected <= numDeltaCommits);
    }

    static String assertCommitMetadata(String expected, String datasetPath, FileSystem fs, int totalCommits)
        throws IOException {
      HoodieTableMetaClient meta = new HoodieTableMetaClient(fs.getConf(), datasetPath);
      HoodieTimeline timeline = meta.getActiveTimeline().getCommitsTimeline().filterCompletedInstants();
      HoodieInstant lastInstant = timeline.lastInstant().get();
      HoodieCommitMetadata commitMetadata = HoodieCommitMetadata.fromBytes(
          timeline.getInstantDetails(lastInstant).get(), HoodieCommitMetadata.class);
      assertEquals(totalCommits, timeline.countInstants());
      assertEquals(expected, commitMetadata.getMetadata(HoodieDeltaStreamer.CHECKPOINT_KEY));
      return lastInstant.getTimestamp();
    }

    static void waitTillCondition(Function<Boolean, Boolean> condition, long timeoutInSecs) throws Exception {
      Future<Boolean> res = Executors.newSingleThreadExecutor().submit(() -> {
        boolean ret = false;
        while (!ret) {
          try {
            Thread.sleep(3000);
            ret = condition.apply(true);
          } catch (Throwable error) {
            log.warn("Got error :", error);
            ret = false;
          }
        }
        return true;
      });
      res.get(timeoutInSecs, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testProps() throws IOException {
    TypedProperties props = new DFSPropertiesConfiguration(
        dfs, new Path(dfsBasePath + "/" + PROPS_FILENAME_TEST_SOURCE)).getConfig();
    assertEquals(2, props.getInteger("hoodie.upsert.shuffle.parallelism"));
    assertEquals("_row_key", props.getString("hoodie.datasource.write.recordkey.field"));
    assertEquals(
        "com.uber.hoodie.utilities.TestHoodieDeltaStreamer$TestGenerator",
        props.getString("hoodie.datasource.write.keygenerator.class")
    );
  }

  @Test
  public void testPropsWithInvalidKeyGenerator() throws Exception {
    try {
      String datasetBasePath = dfsBasePath + "/test_dataset";
      HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
          TestHelpers.makeConfig(
              datasetBasePath, Operation.BULK_INSERT, TripsWithDistanceTransformer.class.getName(),
              PROPS_FILENAME_TEST_INVALID, false), jsc);
      deltaStreamer.sync();
      fail("Should error out when setting the key generator class property to an invalid value");
    } catch (IOException e) {
      //expected
      log.error("Expected error during getting the key generator", e);
      assertTrue(e.getMessage().contains("Could not load key generator class"));
    }
  }

  @Test
  public void testDatasetCreation() throws Exception {
    try {
      dfs.mkdirs(new Path(dfsBasePath + "/not_a_dataset"));
      HoodieDeltaStreamer deltaStreamer = new HoodieDeltaStreamer(
          TestHelpers.makeConfig(dfsBasePath + "/not_a_dataset", Operation.BULK_INSERT), jsc);
      deltaStreamer.sync();
      fail("Should error out when pointed out at a dir thats not a dataset");
    } catch (DatasetNotFoundException e) {
      //expected
      log.error("Expected error during dataset creation", e);
    }
  }

  @Test
  public void testBulkInsertsAndUpserts() throws Exception {
    String datasetBasePath = dfsBasePath + "/test_dataset";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(datasetBasePath, Operation.BULK_INSERT);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00000", datasetBasePath, dfs, 1);

    // No new data => no commits.
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00000", datasetBasePath, dfs, 1);

    // upsert() #1
    cfg.sourceLimit = 2000;
    cfg.operation = Operation.UPSERT;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00001", datasetBasePath, dfs, 2);
    List<Row> counts = TestHelpers.countsPerCommit(datasetBasePath + "/*/*.parquet", sqlContext);
    assertEquals(2000, counts.get(0).getLong(1));
  }

  @Test
  public void testUpsertsCOWContinuousMode() throws Exception {
    testUpsertsContinuousMode(HoodieTableType.COPY_ON_WRITE, "continuous_cow");
  }

  @Test
  public void testUpsertsMORContinuousMode() throws Exception {
    testUpsertsContinuousMode(HoodieTableType.MERGE_ON_READ, "continuous_mor");
  }

  private void testUpsertsContinuousMode(HoodieTableType tableType, String tempDir) throws Exception {
    String datasetBasePath = dfsBasePath + "/" + tempDir;
    // Keep it higher than batch-size to test continuous mode
    int totalRecords = 3000;

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(datasetBasePath, Operation.UPSERT);
    cfg.continuousMode = true;
    cfg.storageType = tableType.name();
    cfg.configs.add(String.format("%s=%d", TestSourceConfig.MAX_UNIQUE_RECORDS_PROP, totalRecords));
    cfg.configs.add(String.format("%s=false", HoodieCompactionConfig.AUTO_CLEAN_PROP));
    HoodieDeltaStreamer ds = new HoodieDeltaStreamer(cfg, jsc);
    Future dsFuture = Executors.newSingleThreadExecutor().submit(() -> {
      try {
        ds.sync();
      } catch (Exception ex) {
        throw new RuntimeException(ex.getMessage(), ex);
      }
    });

    TestHelpers.waitTillCondition((r) -> {
      if (tableType.equals(HoodieTableType.MERGE_ON_READ)) {
        TestHelpers.assertAtleastNDeltaCommits(5, datasetBasePath, dfs);
        TestHelpers.assertAtleastNCompactionCommits(2, datasetBasePath, dfs);
      } else {
        TestHelpers.assertAtleastNCompactionCommits(5, datasetBasePath, dfs);
      }
      TestHelpers.assertRecordCount(totalRecords, datasetBasePath + "/*/*.parquet", sqlContext);
      TestHelpers.assertDistanceCount(totalRecords, datasetBasePath + "/*/*.parquet", sqlContext);
      return true;
    }, 180);
    ds.shutdownGracefully();
    dsFuture.get();
  }

  /**
   * Test Bulk Insert and upserts with hive syncing. Tests Hudi incremental processing using a 2 step pipeline
   * The first step involves using a SQL template to transform a source
   * TEST-DATA-SOURCE  ============================> HUDI TABLE 1   ===============>  HUDI TABLE 2
   *                   (incr-pull with transform)                     (incr-pull)
   * Hudi Table 1 is synced with Hive.
   * @throws Exception
   */
  @Test
  public void testBulkInsertsAndUpsertsWithSQLBasedTransformerFor2StepPipeline() throws Exception {
    String datasetBasePath = dfsBasePath + "/test_dataset2";
    String downstreamDatasetBasePath = dfsBasePath + "/test_downstream_dataset2";

    HiveSyncConfig hiveSyncConfig = getHiveSyncConfig(datasetBasePath, "hive_trips");

    // Initial bulk insert to ingest to first hudi table
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(datasetBasePath, Operation.BULK_INSERT,
        SqlQueryBasedTransformer.class.getName(), PROPS_FILENAME_TEST_SOURCE, true);
    new HoodieDeltaStreamer(cfg, jsc, dfs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    String lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00000", datasetBasePath, dfs, 1);

    // Now incrementally pull from the above hudi table and ingest to second table
    HoodieDeltaStreamer.Config downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(datasetBasePath, downstreamDatasetBasePath, Operation.BULK_INSERT, true);
    new HoodieDeltaStreamer(downstreamCfg, jsc, dfs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamDatasetBasePath, dfs, 1);

    // No new data => no commits for upstream table
    cfg.sourceLimit = 0;
    new HoodieDeltaStreamer(cfg, jsc, dfs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00000", datasetBasePath, dfs, 1);

    // with no change in upstream table, no change in downstream too when pulled.
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(1000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamDatasetBasePath, dfs, 1);

    // upsert() #1 on upstream hudi table
    cfg.sourceLimit = 2000;
    cfg.operation = Operation.UPSERT;
    new HoodieDeltaStreamer(cfg, jsc, dfs, hiveServer.getHiveConf()).sync();
    TestHelpers.assertRecordCount(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    lastInstantForUpstreamTable = TestHelpers.assertCommitMetadata("00001", datasetBasePath, dfs, 2);
    List<Row> counts = TestHelpers.countsPerCommit(datasetBasePath + "/*/*.parquet", sqlContext);
    assertEquals(2000, counts.get(0).getLong(1));

    // Incrementally pull changes in upstream hudi table and apply to downstream table
    downstreamCfg =
        TestHelpers.makeConfigForHudiIncrSrc(datasetBasePath, downstreamDatasetBasePath, Operation.UPSERT, false);
    downstreamCfg.sourceLimit = 2000;
    new HoodieDeltaStreamer(downstreamCfg, jsc).sync();
    TestHelpers.assertRecordCount(2000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCount(2000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertDistanceCountWithExactValue(2000, downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    String finalInstant =
        TestHelpers.assertCommitMetadata(lastInstantForUpstreamTable, downstreamDatasetBasePath, dfs, 2);
    counts = TestHelpers.countsPerCommit(downstreamDatasetBasePath + "/*/*.parquet", sqlContext);
    assertEquals(2000, counts.get(0).getLong(1));

    // Test Hive integration
    HoodieHiveClient hiveClient = new HoodieHiveClient(hiveSyncConfig, hiveServer.getHiveConf(), dfs);
    assertTrue("Table " + hiveSyncConfig.tableName + " should exist",
        hiveClient.doesTableExist());
    assertEquals("Table partitions should match the number of partitions we wrote", 1,
        hiveClient.scanTablePartitions().size());
    assertEquals("The last commit that was sycned should be updated in the TBLPROPERTIES",
        lastInstantForUpstreamTable, hiveClient.getLastCommitTimeSynced().get());
  }

  @Test
  public void testFilterDupes() throws Exception {
    String datasetBasePath = dfsBasePath + "/test_dupes_dataset";

    // Initial bulk insert
    HoodieDeltaStreamer.Config cfg = TestHelpers.makeConfig(datasetBasePath, Operation.BULK_INSERT);
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(1000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00000", datasetBasePath, dfs, 1);

    // Generate the same 1000 records + 1000 new ones for upsert
    cfg.filterDupes = true;
    cfg.sourceLimit = 2000;
    cfg.operation = Operation.UPSERT;
    new HoodieDeltaStreamer(cfg, jsc).sync();
    TestHelpers.assertRecordCount(2000, datasetBasePath + "/*/*.parquet", sqlContext);
    TestHelpers.assertCommitMetadata("00001", datasetBasePath, dfs, 2);
    // 1000 records for commit 00000 & 1000 for commit 00001
    List<Row> counts = TestHelpers.countsPerCommit(datasetBasePath + "/*/*.parquet", sqlContext);
    assertEquals(1000, counts.get(0).getLong(1));
    assertEquals(1000, counts.get(1).getLong(1));
  }

  @Test
  public void testDistributedTestDataSource() throws Exception {
    TypedProperties props = new TypedProperties();
    props.setProperty(TestSourceConfig.MAX_UNIQUE_RECORDS_PROP, "1000");
    props.setProperty(TestSourceConfig.NUM_SOURCE_PARTITIONS_PROP, "1");
    props.setProperty(TestSourceConfig.USE_ROCKSDB_FOR_TEST_DATAGEN_KEYS, "true");
    DistributedTestDataSource distributedTestDataSource = new DistributedTestDataSource(props,
        jsc, sparkSession, null);
    InputBatch<JavaRDD<GenericRecord>> batch = distributedTestDataSource.fetchNext(Optional.empty(), 10000000);
    batch.getBatch().get().cache();
    long c = batch.getBatch().get().count();
    Assert.assertEquals(1000, c);
  }

  /**
   * UDF to calculate Haversine distance
   */
  public static class DistanceUDF implements UDF4<Double, Double, Double, Double, Double> {

    /**
     *
     * Taken from https://stackoverflow.com/questions/3694380/calculating-distance-between-two-points-using-latitude-
     * longitude-what-am-i-doi
     * Calculate distance between two points in latitude and longitude taking
     * into account height difference. If you are not interested in height
     * difference pass 0.0. Uses Haversine method as its base.
     *
     * lat1, lon1 Start point lat2, lon2 End point el1 Start altitude in meters
     * el2 End altitude in meters
     * @returns Distance in Meters
     */
    @Override
    public Double call(Double lat1, Double lat2, Double lon1, Double lon2) {

      final int R = 6371; // Radius of the earth

      double latDistance = Math.toRadians(lat2 - lat1);
      double lonDistance = Math.toRadians(lon2 - lon1);
      double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
          + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
          * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
      double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
      double distance = R * c * 1000; // convert to meters

      double height = 0;

      distance = Math.pow(distance, 2) + Math.pow(height, 2);

      return Math.sqrt(distance);
    }
  }

  /**
   * Adds a new field "haversine_distance" to the row
   */
  public static class TripsWithDistanceTransformer implements Transformer {

    @Override
    public Dataset<Row> apply(JavaSparkContext jsc, SparkSession sparkSession,
        Dataset<Row> rowDataset, TypedProperties properties) {
      rowDataset.sqlContext().udf().register("distance_udf", new DistanceUDF(), DataTypes.DoubleType);
      return rowDataset.withColumn("haversine_distance",
          functions.callUDF("distance_udf", functions.col("begin_lat"),
              functions.col("end_lat"), functions.col("begin_lon"), functions.col("end_lat")));
    }
  }

  public static class TestGenerator extends SimpleKeyGenerator {

    public TestGenerator(TypedProperties props) {
      super(props);
    }
  }
}

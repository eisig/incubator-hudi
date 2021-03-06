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

package com.uber.hoodie.utilities.sources;

import static org.junit.Assert.assertEquals;

import com.uber.hoodie.AvroConversionUtils;
import com.uber.hoodie.common.HoodieTestDataGenerator;
import com.uber.hoodie.common.util.TypedProperties;
import com.uber.hoodie.utilities.UtilitiesTestBase;
import com.uber.hoodie.utilities.deltastreamer.SourceFormatAdapter;
import com.uber.hoodie.utilities.schema.FilebasedSchemaProvider;
import java.io.IOException;
import java.util.Optional;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.fs.Path;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Basic tests against all subclasses of {@link JsonDFSSource}
 */
public class TestDFSSource extends UtilitiesTestBase {

  private FilebasedSchemaProvider schemaProvider;

  @BeforeClass
  public static void initClass() throws Exception {
    UtilitiesTestBase.initClass();
  }

  @AfterClass
  public static void cleanupClass() throws Exception {
    UtilitiesTestBase.cleanupClass();
  }

  @Before
  public void setup() throws Exception {
    super.setup();
    schemaProvider = new FilebasedSchemaProvider(Helpers.setupSchemaOnDFS(), jsc);
  }

  @After
  public void teardown() throws Exception {
    super.teardown();
  }

  @Test
  public void testJsonDFSSource() throws IOException {
    dfs.mkdirs(new Path(dfsBasePath + "/jsonFiles"));
    HoodieTestDataGenerator dataGenerator = new HoodieTestDataGenerator();

    TypedProperties props = new TypedProperties();
    props.setProperty("hoodie.deltastreamer.source.dfs.root", dfsBasePath + "/jsonFiles");
    JsonDFSSource jsonDFSSource = new JsonDFSSource(props, jsc, sparkSession, schemaProvider);
    SourceFormatAdapter jsonSource = new SourceFormatAdapter(jsonDFSSource);

    // 1. Extract without any checkpoint => get all the data, respecting sourceLimit
    assertEquals(Optional.empty(), jsonSource.fetchNewDataInAvroFormat(Optional.empty(), Long.MAX_VALUE).getBatch());
    UtilitiesTestBase.Helpers.saveStringsToDFS(
        Helpers.jsonifyRecords(dataGenerator.generateInserts("000", 100)), dfs,
        dfsBasePath + "/jsonFiles/1.json");
    assertEquals(Optional.empty(), jsonSource.fetchNewDataInAvroFormat(Optional.empty(), 10).getBatch());
    InputBatch<JavaRDD<GenericRecord>> fetch1 =
        jsonSource.fetchNewDataInAvroFormat(Optional.empty(), 1000000);
    assertEquals(100, fetch1.getBatch().get().count());
    // Test json -> Row format
    InputBatch<Dataset<Row>> fetch1AsRows =
        jsonSource.fetchNewDataInRowFormat(Optional.empty(), 1000000);
    assertEquals(100, fetch1AsRows.getBatch().get().count());
    // Test Avro -> Row format
    Dataset<Row> fetch1Rows = AvroConversionUtils.createDataFrame(JavaRDD.toRDD(fetch1.getBatch().get()),
        schemaProvider.getSourceSchema().toString(), jsonDFSSource.getSparkSession());
    assertEquals(100, fetch1Rows.count());

    // 2. Produce new data, extract new data
    UtilitiesTestBase.Helpers.saveStringsToDFS(
        Helpers.jsonifyRecords(dataGenerator.generateInserts("001", 10000)),
        dfs, dfsBasePath + "/jsonFiles/2.json");
    InputBatch<Dataset<Row>> fetch2 = jsonSource.fetchNewDataInRowFormat(
        Optional.of(fetch1.getCheckpointForNextBatch()), Long.MAX_VALUE);
    assertEquals(10000, fetch2.getBatch().get().count());

    // 3. Extract with previous checkpoint => gives same data back (idempotent)
    InputBatch<Dataset<Row>> fetch3 = jsonSource.fetchNewDataInRowFormat(
        Optional.of(fetch1.getCheckpointForNextBatch()), Long.MAX_VALUE);
    assertEquals(10000, fetch3.getBatch().get().count());
    assertEquals(fetch2.getCheckpointForNextBatch(), fetch3.getCheckpointForNextBatch());
    fetch3.getBatch().get().registerTempTable("test_dfs_table");
    Dataset<Row> rowDataset = new SQLContext(jsc.sc()).sql("select * from test_dfs_table");
    assertEquals(10000, rowDataset.count());

    // 4. Extract with latest checkpoint => no new data returned
    InputBatch<JavaRDD<GenericRecord>> fetch4 = jsonSource.fetchNewDataInAvroFormat(
        Optional.of(fetch2.getCheckpointForNextBatch()), Long.MAX_VALUE);
    assertEquals(Optional.empty(), fetch4.getBatch());
  }
}

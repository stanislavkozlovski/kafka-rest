/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package io.confluent.kafkarest.unit;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.confluent.kafkarest.BinaryConsumerState;
import io.confluent.kafkarest.ConsumerManager;
import io.confluent.kafkarest.Errors;
import io.confluent.kafkarest.KafkaRestConfig;
import io.confluent.kafkarest.MetadataObserver;
import io.confluent.kafkarest.entities.BinaryConsumerRecord;
import io.confluent.kafkarest.entities.ConsumerInstanceConfig;
import io.confluent.kafkarest.entities.ConsumerRecord;
import io.confluent.kafkarest.entities.EmbeddedFormat;
import io.confluent.kafkarest.entities.TopicPartitionOffset;
import io.confluent.kafkarest.mock.MockConsumerConnector;
import io.confluent.kafkarest.mock.MockTime;
import io.confluent.rest.RestConfigException;
import io.confluent.rest.exceptions.RestException;
import io.confluent.rest.exceptions.RestNotFoundException;
import kafka.consumer.ConsumerConfig;
import kafka.javaapi.consumer.ConsumerConnector;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests basic create/read/commit/delete functionality of ConsumerManager. This only exercises the
 * functionality for binary data because it uses a mock consumer that only works with byte[] data.
 */
public class ConsumerManagerTest {

  private Properties properties;
  private KafkaRestConfig config;
  private MetadataObserver mdObserver;
  private ConsumerManager.ConsumerFactory consumerFactory;
  private ConsumerManager consumerManager;

  private static final String groupName = "testgroup";
  private static final String topicName = "testtopic";
  private static final String secondTopicName = "testtopic2";

  // Setup holding vars for results from callback
  private boolean sawCallback = false;
  private static Exception actualException = null;
  private static List<? extends ConsumerRecord<byte[], byte[]>> actualRecords = null;
  private int actualLength = 0;
  private static List<TopicPartitionOffset> actualOffsets = null;

  private Capture<ConsumerConfig> capturedConsumerConfig;

  @Before
  public void setUp() throws RestConfigException {
    this.properties = new Properties();
    properties.setProperty(KafkaRestConfig.CONSUMER_REQUEST_MAX_BYTES_CONFIG, "1024");
    // This setting supports the testConsumerOverrides test. It is otherwise benign and should
    // not affect other tests.
    properties.setProperty("exclude.internal.topics", "false");
    setUp(properties);
  }

  public void setUp(Properties properties) throws RestConfigException {
    config = new KafkaRestConfig(properties, new MockTime());
    mdObserver = EasyMock.createMock(MetadataObserver.class);
    consumerFactory = EasyMock.createMock(ConsumerManager.ConsumerFactory.class);
    consumerManager = new ConsumerManager(config, mdObserver, consumerFactory);
  }

  @After
  public void tearDown() {
    consumerManager.shutdown();
  }

  private ConsumerConnector expectCreate(
      Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>> schedules) {
    return expectCreate(schedules, false, null);
  }

  private ConsumerConnector expectCreate(
      Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>> schedules,
      boolean allowMissingSchedule, String requestedId) {
    ConsumerConnector
        consumer =
        new MockConsumerConnector(
            config.getTime(), "testclient", schedules,
            Integer.parseInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_DEFAULT),
            allowMissingSchedule);
    capturedConsumerConfig = new Capture<ConsumerConfig>();
    EasyMock.expect(consumerFactory.createConsumer(EasyMock.capture(capturedConsumerConfig)))
                        .andReturn(consumer);
    return consumer;
  }

  // Expect a Kafka consumer to be created, but return it with no data in its queue. Used to test
  // functionality that doesn't rely on actually consuming the data.
  private ConsumerConnector expectCreateNoData(String requestedId) {
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>> referenceSchedule
        = new HashMap<Integer, List<ConsumerRecord<byte[], byte[]>>>();
    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>> schedules
        = new HashMap<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    return expectCreate(schedules, true, requestedId);
  }

  private ConsumerConnector expectCreateNoData() {
    return expectCreateNoData(null);
  }

  @Test
  public void testConsumerOverrides() {
    ConsumerConnector consumer =
        new MockConsumerConnector(
            config.getTime(), "testclient", null,
            Integer.parseInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_DEFAULT),
            true);
    final Capture<ConsumerConfig> consumerConfig = new Capture<ConsumerConfig>();
    EasyMock.expect(consumerFactory.createConsumer(EasyMock.capture(consumerConfig)))
        .andReturn(consumer);

    EasyMock.replay(consumerFactory);

    String cid = consumerManager.createConsumer(
        groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    // The exclude.internal.topics setting is overridden via the constructor when the
    // ConsumerManager is created, and we can make sure it gets set properly here.
    assertFalse(consumerConfig.getValue().excludeInternalTopics());

    EasyMock.verify(consumerFactory);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testConsumerNormalOps() throws InterruptedException, ExecutionException {
    // Tests create instance, read, and delete
    final List<ConsumerRecord<byte[], byte[]>> referenceRecords = referenceRecords(3);
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>>
        referenceSchedule =
            new HashMap<>();
    referenceSchedule.put(50, referenceRecords);

    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
        schedules =
        new HashMap<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    expectCreate(schedules);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);

    EasyMock.replay(mdObserver, consumerFactory);
    String cid = consumerManager.createConsumer(groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    readFromDefault(cid);

    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    assertEquals("Records returned not as expected", referenceRecords, actualRecords);
    // With # of bytes in messages < max bytes per response, this should finish just after
    // the per-request timeout (because the timeout perfectly coincides with a scheduled
    // iteration when using the default settings).
    assertEquals(config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG)
                  + config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG),
                  config.getTime().milliseconds());

    sawCallback = false;
    actualException = null;
    actualOffsets = null;
    consumerManager.commitOffsets(groupName, cid, new ConsumerManager.CommitCallback() {
      @Override
      public void onCompletion(List<TopicPartitionOffset> offsets, Exception e) {
        sawCallback = true;

        actualException = e;
        actualOffsets = offsets;
      }
    }).get();
    assertTrue("Callback not called", sawCallback);
    assertNull("Callback exception", actualException);
    // Mock consumer doesn't handle offsets, so we just check we get some output for the
    // right partitions
    assertNotNull("Callback Offsets", actualOffsets);
    assertEquals("Callback Offsets Size", 3, actualOffsets.size());

    consumerManager.deleteConsumer(groupName, cid);

    EasyMock.verify(mdObserver, consumerFactory);
  }

  /**
   * consumer.request.timeout.ms should not modify how long the proxy waits until returning a response
   */
  @Test
  public void testConsumerRequestTimeoutDoesNotModifyProxyResponseTime() throws Exception {
    properties.setProperty(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG, "2500");
    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
            schedules =
            new HashMap<>();
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>> referenceSchedule = new HashMap<>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));
    expectCreate(schedules);

    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    readFromDefault(consumerManager.createConsumer(groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY)));

    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    // should wait default wait.ms time
    assertEquals(Integer.parseInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_DEFAULT),
            config.getInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG));
    assertEquals(config.getInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG),
            config.getTime().milliseconds(),
            config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG));
  }

  /**
   * Response should return no sooner than KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG
   */
  @Test
  public void testConsumerWaitMs() throws Exception {
    properties.setProperty(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG, "139");
    setUp(properties);
    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
            schedules =
            new HashMap<>();
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>> referenceSchedule = new HashMap<>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));
    expectCreate(schedules);

    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    readFromDefault(consumerManager.createConsumer(groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY)));

    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    assertEquals(config.getInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG),
            config.getTime().milliseconds(),
            config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG));
  }

  /**
   * When min.bytes is fulfilled, we should return immediately
   */
  @Test
  public void testConsumerWaitMsAndMinBytes() throws Exception {
    properties.setProperty(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG, "1303");
    properties.setProperty(KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG, "1");
    setUp(properties);

    final List<ConsumerRecord<byte[], byte[]>> referenceRecords = referenceRecords(3);
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>>
            referenceSchedule = new HashMap<>();
    referenceSchedule.put(50, referenceRecords);

    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
            schedules = new HashMap<>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    expectCreate(schedules);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    String cid = consumerManager.createConsumer(groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    long startTime = System.currentTimeMillis();
    readFromDefault(cid);


    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    // should return first record immediately since min.bytes is fulfilled
    assertEquals("Records returned not as expected",
            Arrays.asList(referenceRecords.get(0)), actualRecords);
    long estimatedTime = System.currentTimeMillis() - startTime;
    int waitMs = config.getInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG);
    assertTrue(estimatedTime < waitMs); // should have returned earlier than min.wait.ms
  }

  @Test
  public void testConsumeMinBytesIsOverridablePerConsumer() throws Exception {
    properties.setProperty(KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG, "10");
    // global settings should return more than one record immediately
    setUp(properties);

    final List<ConsumerRecord<byte[], byte[]>> referenceRecords = referenceRecords(3);
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>>
            referenceSchedule = new HashMap<>();
    referenceSchedule.put(50, referenceRecords);

    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
            schedules = new HashMap<>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    expectCreate(schedules);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    ConsumerInstanceConfig config = new ConsumerInstanceConfig(EmbeddedFormat.BINARY);
    // we expect one record to be returned since the setting is overridden
    config.setResponseMinBytes(Integer.toString(1));
    readFromDefault(consumerManager.createConsumer(groupName, config));

    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    // should return first record immediately since min.bytes is fulfilled
    assertEquals("Records returned not as expected",
            Arrays.asList(referenceRecords.get(0)), actualRecords);
  }

  /**
   * Response should return no sooner than the overridden PROXY_FETCH_MAX_WAIT_MS_CONFIG
   */
  @Test
  public void testConsumerWaitMsIsOverriddablePerConsumer() throws Exception {
    Integer overriddenWaitTimeMs = 111;
    properties.setProperty(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG, "1201");
    setUp(properties);
    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
            schedules =
            new HashMap<>();
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>> referenceSchedule = new HashMap<>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));
    expectCreate(schedules);

    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    ConsumerInstanceConfig consumerConfig = new ConsumerInstanceConfig(EmbeddedFormat.BINARY);
    consumerConfig.setRequestWaitMs(overriddenWaitTimeMs.toString());
    String cid = consumerManager.createConsumer(groupName, consumerConfig);
    sawCallback = false;
    actualException = null;
    actualRecords = null;
    consumerManager.readTopic(
            groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
            (ConsumerManager.ReadCallback<byte[], byte[]>) (records, e) -> {
              actualException = e;
              actualRecords = records;
              sawCallback = true;
            }).get();

    assertTrue("Callback failed to fire", sawCallback);
    assertNull("No exception in callback", actualException);
    assertEquals(overriddenWaitTimeMs, config.getTime().milliseconds(),
            config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG));
  }

  @Test
  public void testConsumerMaxBytesResponse() throws InterruptedException, ExecutionException {
    // Tests that when there are more records available than the max bytes to be included in the
    // response, not all of it is returned.
    final List<ConsumerRecord<byte[], byte[]>> referenceRecords
        = Arrays.<ConsumerRecord<byte[], byte[]>>asList(
        // Don't use 512 as this happens to fall on boundary
        new BinaryConsumerRecord(topicName, null, new byte[511], 0, 0),
        new BinaryConsumerRecord(topicName, null, new byte[511], 1, 0),
        new BinaryConsumerRecord(topicName, null, new byte[511], 2, 0),
        new BinaryConsumerRecord(topicName, null, new byte[511], 3, 0)
    );
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>> referenceSchedule
        = new HashMap<Integer, List<ConsumerRecord<byte[], byte[]>>>();
    referenceSchedule.put(50, referenceRecords);

    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>> schedules
        = new HashMap<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    expectCreate(schedules);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);

    EasyMock.replay(mdObserver, consumerFactory);

    String cid = consumerManager.createConsumer(
        groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    // Ensure vars used by callback are correctly initialised.
    sawCallback = false;
    actualException = null;
    actualLength = 0;
    consumerManager.readTopic(
        groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
        new ConsumerManager.ReadCallback<byte[], byte[]>() {
          @Override
          public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                   Exception e) {
            sawCallback = true;
            actualException = e;
            // Should only see the first two messages since the third pushes us over the limit.
            actualLength = records.size();
          }
        }).get();
    assertTrue("Callback failed to fire", sawCallback);
    assertNull("Callback received exception", actualException);
    // Should only see the first two messages since the third pushes us over the limit.
    assertEquals("List of records returned incorrect", 2, actualLength);

    // Because we should have returned due to the message size limit we shouldn't have
    // maxed out the timeout
    //
    String msg = "Time taken (" + Long.toString(config.getTime().milliseconds()) + ") to process message should be less than the timeout " +
        Integer.toString(config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG)
                 + config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG)) ;
    assertFalse(msg, config.getInt(KafkaRestConfig.CONSUMER_REQUEST_TIMEOUT_MS_CONFIG)
                 + config.getInt(KafkaRestConfig.CONSUMER_ITERATOR_TIMEOUT_MS_CONFIG) < config.getTime().milliseconds());

    // Also check the user-submitted limit
    sawCallback = false;
    actualException = null;
    actualLength = 0;
    consumerManager.readTopic(
        groupName, cid, topicName, BinaryConsumerState.class, 512,
            (ConsumerManager.ReadCallback<byte[], byte[]>) (records, e) -> {
              sawCallback = true;
              actualException = e;
              // Should only see the first two messages since the third pushes us over the limit.
              actualLength = records.size();
            }).get();
    assertTrue("Callback failed to fire", sawCallback);
    assertNull("Callback received exception", actualException);
    // Should only see the first two messages since the third pushes us over the limit.
    assertEquals("List of records returned incorrect", 1, actualLength);

    consumerManager.deleteConsumer(groupName, cid);

    EasyMock.verify(mdObserver, consumerFactory);
  }

  @Test
  public void testIDOverridesName() {
    // We should remain compatible with the original use of consumer IDs, even if it shouldn't
    // really be used. Specifying any ID should override any naming to ensure the same behavior
    expectCreateNoData("id");
    EasyMock.replay(mdObserver, consumerFactory);

    String cid = consumerManager.createConsumer(
        groupName,
        new ConsumerInstanceConfig("id", "name", EmbeddedFormat.BINARY.toString(), null, null, null, null)
    );
    assertEquals("id", cid);
    assertEquals("id", capturedConsumerConfig.getValue().consumerId().getOrElse(null));
    EasyMock.verify(mdObserver, consumerFactory);
  }

  @Test
  public void testDuplicateConsumerName() {
    expectCreateNoData();
    EasyMock.replay(mdObserver, consumerFactory);

    consumerManager.createConsumer(
        groupName,
        new ConsumerInstanceConfig(null, "name", EmbeddedFormat.BINARY.toString(), null, null, null, null)
    );

    try {
      consumerManager.createConsumer(
          groupName,
          new ConsumerInstanceConfig(null, "name", EmbeddedFormat.BINARY.toString(), null, null, null, null)
      );
      fail("Expected to see exception because consumer already exists");
    } catch (RestException e) {
      // expected
      assertEquals(Errors.CONSUMER_ALREADY_EXISTS_ERROR_CODE, e.getErrorCode());
    }

    EasyMock.verify(mdObserver, consumerFactory);
  }

  @Test
  public void testMultipleTopicSubscriptionsFail() throws InterruptedException, ExecutionException {
    expectCreateNoData();
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true);
    EasyMock.expect(mdObserver.topicExists(secondTopicName)).andReturn(true);
    EasyMock.replay(mdObserver, consumerFactory);

    String cid = consumerManager.createConsumer(groupName,
                                                new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    sawCallback = false;
    actualException = null;
    actualRecords = null;
    consumerManager.readTopic(
        groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
        new ConsumerManager.ReadCallback<byte[], byte[]>() {
          @Override
          public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                   Exception e) {
            sawCallback = true;
            actualException = e;
            actualRecords = records;
          }
        }).get();
    assertTrue("Callback not called", sawCallback);
    assertNull("Callback exception", actualException);
    assertEquals("Callback records should be valid but of 0 size", 0, actualRecords.size());


    // Attempt to read from second topic should result in an exception
    sawCallback = false;
    actualException = null;
    actualRecords = null;
    consumerManager.readTopic(
        groupName, cid, secondTopicName, BinaryConsumerState.class, Long.MAX_VALUE,
        new ConsumerManager.ReadCallback<byte[], byte[]>() {
          @Override
          public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                   Exception e) {
            sawCallback = true;
            actualException = e;
            actualRecords = records;
          }
        }).get();
    assertTrue("Callback failed to fire", sawCallback);
    assertNotNull("Callback failed to receive an exception", actualException);
    assertTrue("Callback Exception should be an instance of RestException", actualException instanceof RestException);
    assertEquals("Callback Exception should be for already subscribed consumer", Errors.CONSUMER_ALREADY_SUBSCRIBED_ERROR_CODE,
                 ((RestException) actualException).getErrorCode());
    assertNull("Given an exception occurred in callback shouldn't be any records returned", actualRecords);

    consumerManager.deleteConsumer(groupName, cid);

    EasyMock.verify(mdObserver, consumerFactory);
  }

  @Test
  public void testReadInvalidInstanceFails() {
    readAndExpectImmediateNotFound("invalid", topicName);
  }

  @Test
  public void testReadInvalidTopicFails() throws InterruptedException, ExecutionException {
    final String invalidTopicName = "invalidtopic";
    expectCreate(null);
    EasyMock.expect(mdObserver.topicExists(invalidTopicName)).andReturn(false);

    EasyMock.replay(mdObserver, consumerFactory);

    String instanceId = consumerManager.createConsumer(
        groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));
    readAndExpectImmediateNotFound(instanceId, invalidTopicName);

    EasyMock.verify(mdObserver, consumerFactory);
  }

  @Test(expected = RestNotFoundException.class)
  public void testDeleteInvalidConsumer() {
    consumerManager.deleteConsumer(groupName, "invalidinstance");
  }


  @Test
  public void testConsumerExceptions() throws InterruptedException, ExecutionException {
    // We should be able to handle an exception thrown by the consumer, then issue another
    // request that succeeds and still see all the data
    final List<ConsumerRecord<byte[], byte[]>> referenceRecords = referenceRecords(3);
    referenceRecords.add(null); // trigger exception
    Map<Integer, List<ConsumerRecord<byte[], byte[]>>>
        referenceSchedule =
        new HashMap<Integer, List<ConsumerRecord<byte[], byte[]>>>();
    referenceSchedule.put(50, referenceRecords);

    Map<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>
        schedules =
        new HashMap<String, List<Map<Integer, List<ConsumerRecord<byte[], byte[]>>>>>();
    schedules.put(topicName, Arrays.asList(referenceSchedule));

    expectCreate(schedules);
    EasyMock.expect(mdObserver.topicExists(topicName)).andReturn(true).times(2);

    EasyMock.replay(mdObserver, consumerFactory);

    String cid = consumerManager.createConsumer(
        groupName, new ConsumerInstanceConfig(EmbeddedFormat.BINARY));

    // First read should result in exception.
    sawCallback = false;
    actualException = null;
    actualRecords = null;
    consumerManager.readTopic(
        groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
        new ConsumerManager.ReadCallback<byte[], byte[]>() {
          @Override
          public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                   Exception e) {
            sawCallback = true;
            actualRecords = records;
            actualException = e;
          }
        }).get();
    assertTrue("Callback not called", sawCallback);
    assertNotNull("Callback exception should be populated", actualException);
    assertNull("Callback with exception should not have any records", actualRecords);

    // Second read should recover and return all the data.
    sawCallback = false;
    consumerManager.readTopic(
        groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
        new ConsumerManager.ReadCallback<byte[], byte[]>() {
          @Override
          public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                   Exception e) {
            sawCallback = true;
            assertNull(e);
            assertEquals(referenceRecords, records);
          }
        }).get();
    assertTrue(sawCallback);

    EasyMock.verify(mdObserver, consumerFactory);
  }

  /**
   * Returns a list of one record per partition, up to {@code count} partitions
   */
  private List<ConsumerRecord<byte[], byte[]>> referenceRecords(int count) {
    return IntStream.range(0, count).mapToObj(i -> new BinaryConsumerRecord(topicName,
            ("k" + (i + 1)).getBytes(),
            ("v" + (i + 1)).getBytes(), i, 0)).collect(Collectors.toList());
  }

  private void readFromDefault(String cid) throws InterruptedException, ExecutionException {
    sawCallback = false;
    actualException = null;
    actualRecords = null;
    consumerManager.readTopic(
            groupName, cid, topicName, BinaryConsumerState.class, Long.MAX_VALUE,
            (ConsumerManager.ReadCallback<byte[], byte[]>) (records, e) -> {
              actualException = e;
              actualRecords = records;
              sawCallback = true;
            }).get();
  }

  private void readAndExpectImmediateNotFound(String cid, String topic) {
    sawCallback = false;
    actualRecords = null;
    actualException = null;
    Future
        future =
        consumerManager.readTopic(
            groupName, cid, topic, BinaryConsumerState.class, Long.MAX_VALUE,
            new ConsumerManager.ReadCallback<byte[], byte[]>() {
              @Override
              public void onCompletion(List<? extends ConsumerRecord<byte[], byte[]>> records,
                                       Exception e) {
                sawCallback = true;
                actualRecords = records;
                actualException = e;
              }
            });
    assertTrue("Callback not called", sawCallback);
    assertNull("Callback records", actualRecords);
    assertThat("Callback exception is RestNotFound", actualException, instanceOf(RestNotFoundException.class));
    assertNull(future);
  }
}

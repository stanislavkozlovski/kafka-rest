/*
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

package io.confluent.kafkarest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Vector;

import io.confluent.kafkarest.entities.ConsumerRecord;
import io.confluent.rest.exceptions.RestException;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.message.MessageAndMetadata;

/**
 * State for tracking the progress of a single consumer read request.
 *
 * <p>To support embedded formats that require translation between the format deserialized by the
 * Kafka decoder and the format returned in the ConsumerRecord entity sent back to the client,
 * this class uses two pairs of key-value generic type parameters: KafkaK/KafkaV is the format
 * returned by the Kafka consumer's decoder/deserializer, ClientK/ClientV is the format
 * returned to the client in the HTTP response. In some cases these may be identical.
 */
class ConsumerReadTask<KafkaKeyT, KafkaValueT, ClientKeyT, ClientValueT> {
  private static final Logger log = LoggerFactory.getLogger(ConsumerReadTask.class);

  private ConsumerState parent;
  private final long maxResponseBytes;
  private final int requestTimeoutMs;
  // the minimum bytes the task should accumulate
  // before returning a response (or hitting the timeout)
  // responseMinBytes might be bigger than maxResponseBytes
  // in cases where the functionality is disabled
  private final int responseMinBytes;
  private final ConsumerWorkerReadCallback<ClientKeyT, ClientValueT> callback;
  private boolean finished;

  private ConsumerTopicState topicState;
  private ConsumerIterator<KafkaKeyT, KafkaValueT> iter;
  private List<ConsumerRecord<ClientKeyT, ClientValueT>> messages;
  private KafkaRestConfig config;
  private long bytesConsumed = 0;
  private boolean exceededMinResponseBytes = false;
  private boolean willExceedMaxResponseBytes = false;
  private final long started;

  // Expiration if this task is waiting, considering both the expiration of the whole task and
  // a single backoff, if one is in progress
  long waitExpiration;

  public ConsumerReadTask(
      ConsumerState parent,
      String topic,
      long maxBytes,
      KafkaRestConfig config,
      ConsumerWorkerReadCallback<ClientKeyT, ClientValueT> callback
  ) {
    KafkaRestConfig conf = parent.getConfig();
    this.parent = parent;
    this.maxResponseBytes = Math.min(
        maxBytes,
        conf.consumerResponseMaxBytes()
    );
    this.callback = callback;
    this.finished = false;
    this.config = config;

    this.requestTimeoutMs = conf.getInt(KafkaRestConfig.PROXY_FETCH_MAX_WAIT_MS_CONFIG);
    int responseMinBytes = conf.getInt(KafkaRestConfig.PROXY_FETCH_MIN_BYTES_CONFIG);
    this.responseMinBytes = responseMinBytes < 0 ? Integer.MAX_VALUE : responseMinBytes;

    started = conf.getTime().milliseconds();
    try {
      topicState = parent.getOrCreateTopicState(topic);

      // If the previous call failed, restore any outstanding data into this task.
      ConsumerReadTask previousTask = topicState.clearFailedTask();
      if (previousTask != null) {
        this.messages = previousTask.messages;
        this.exceededMinResponseBytes = previousTask.exceededMinResponseBytes;
        this.willExceedMaxResponseBytes = previousTask.willExceedMaxResponseBytes;
        this.bytesConsumed = previousTask.bytesConsumed;
      }
    } catch (RestException e) {
      finish(e);
    }
  }

  public void doFullRead() {
    log.trace("Executing consumer read task ({})", this);
    while (!isDone()) {
      doPartialRead();
      long now = config.getTime().milliseconds();
      long waitTime = waitExpiration - now;
      if (waitTime > 0) {
        config.getTime().sleep(waitTime);
      }
    }
    log.trace("Finished executing consumer read task ({})", this);
  }

  /**
   * Performs one iteration of reading from a consumer iterator.
   *
   * @return true if this read timed out, indicating the scheduler should back off
   */
  public boolean doPartialRead() {
    try {
      // Initial setup requires locking, which must be done on this thread.
      if (iter == null) {
        parent.startRead(topicState);
        iter = topicState.getIterator();

        messages = new Vector<>();
        waitExpiration = 0;
      }

      boolean backoff = false;
      long roughMsgSize = 0;

      long startedIteration = parent.getConfig().getTime().milliseconds();

      try {
        // Read off as many messages as we can without triggering a timeout exception. The
        // consumer timeout should be set very small, so the expectation is that even in the
        // worst case, num_messages * consumer_timeout << request_timeout, so it's safe to only
        // check the elapsed time once this loop finishes.
        while (iter.hasNext()) {
          MessageAndMetadata<KafkaKeyT, KafkaValueT> msg = iter.peek();
          ConsumerRecordAndSize<ClientKeyT, ClientValueT> recordAndSize =
              parent.createConsumerRecord(msg);
          roughMsgSize = recordAndSize.getSize();
          this.willExceedMaxResponseBytes = bytesConsumed + roughMsgSize >= maxResponseBytes;
          if (this.willExceedMaxResponseBytes) {
            break;
          }

          iter.next();
          messages.add(recordAndSize.getRecord());
          bytesConsumed += roughMsgSize;
          this.exceededMinResponseBytes = bytesConsumed > responseMinBytes;
          if (this.exceededMinResponseBytes) {
            break;
          }
          // Updating the consumed offsets isn't done until we're actually going to return the
          // data since we may encounter an error during a subsequent read, in which case we'll
          // have to defer returning the data so we can return an HTTP error instead
        }
      } catch (ConsumerTimeoutException cte) {
        log.trace("ConsumerReadTask timed out, using backoff id={}", this);
        backoff = true;
      }

      log.trace(
          "ConsumerReadTask exiting read with id={} messages={} bytes={}",
          this,
          messages.size(),
          bytesConsumed
      );

      long now = parent.getConfig().getTime().milliseconds();
      long elapsed = now - started;
      // Compute backoff based on starting time. This makes reasoning about when timeouts
      // should occur simpler for tests.
      int itbackoff
          = parent.getConfig().getInt(KafkaRestConfig.CONSUMER_ITERATOR_BACKOFF_MS_CONFIG);
      long backoffExpiration = startedIteration + itbackoff;
      long requestExpiration = started + requestTimeoutMs;
      waitExpiration = Math.min(backoffExpiration, requestExpiration);

      // Including the rough message size here ensures processing finishes if the next
      // message exceeds the maxResponseBytes
      boolean requestTimedOut = elapsed >= requestTimeoutMs;
      if (requestTimedOut || willExceedMaxResponseBytes || exceededMinResponseBytes) {
        log.trace("Finishing ConsumerReadTask id={} requestTimedOut={} "
                  + "willExceedMaxResponseBytes={} exceededMinResponseBytes={}",
                  this, requestTimedOut, willExceedMaxResponseBytes, this.exceededMinResponseBytes
        );
        finish();
      }

      return backoff;
    } catch (Exception e) {
      finish(e);
      log.error("Unexpected exception in consumer read task id={} ", this, e);
      return false;
    }
  }

  boolean isDone() {
    return finished;
  }

  private void finish() {
    finish(null);
  }

  private void finish(Exception e) {
    log.trace("Finishing ConsumerReadTask id={} exception={}", this, e);
    if (e == null) {
      // Now it's safe to mark these messages as consumed by updating offsets since we're actually
      // going to return the data.
      Map<Integer, Long> consumedOffsets = topicState.getConsumedOffsets();
      for (ConsumerRecord<ClientKeyT, ClientValueT> msg : messages) {
        consumedOffsets.put(msg.getPartition(), msg.getOffset());
      }
    } else {
      // If we read any messages before the exception occurred, keep this task so we don't lose
      // messages. Subsequent reads will add the outstanding messages before attempting to read
      // any more from the consumer stream iterator
      if (topicState != null && messages != null && messages.size() > 0) {
        log.trace("Saving failed ConsumerReadTask for subsequent call id={}", this, e);
        topicState.setFailedTask(this);
      }
    }
    if (topicState != null) { // May have failed trying to get topicState
      parent.finishRead(topicState);
    }
    try {
      callback.onCompletion((e == null) ? messages : null, e);
    } catch (Throwable t) {
      // This protects the worker thread from any issues with the callback code. Nothing to be
      // done here but log it since it indicates a bug in the calling code.
      log.error("Consumer read callback threw an unhandled exception id={}", this, e);
    }
    finished = true;
  }

}

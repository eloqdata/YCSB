/*
 * Copyright (c) 2026 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package site.ycsb.db;

import com.mongodb.MongoException;
import org.junit.Test;

import site.ycsb.DBException;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EloqDocClientTest {
  @Test
  public void retriesEloqOutOfMemory() throws Exception {
    EloqDocClient client = new EloqDocClient(1000, 0);
    AtomicInteger calls = new AtomicInteger();
    String result = client.execute(() -> {
      if (calls.incrementAndGet() < 3) {
        throw new MongoException(146, "TxError[22]: OUT_OF_MEMORY");
      }
      return "ok";
    });
    assertEquals("ok", result);
    assertEquals(3, calls.get());
  }

  @Test
  public void retriesReadWriteAndWriteConflicts() throws Exception {
    for (int code : new int[] {246, 112}) {
      EloqDocClient client = new EloqDocClient(1000, 0);
      AtomicInteger calls = new AtomicInteger();
      assertEquals("ok", client.execute(() -> {
        if (calls.incrementAndGet() == 1) {
          throw new MongoException(code, "TxError[9]: conflict");
        }
        return "ok";
      }));
      assertEquals(2, calls.get());
    }
  }

  @Test
  public void leavesOtherMongoErrorsUntouched() throws Exception {
    for (MongoException error : new MongoException[] {
        new MongoException(11000, "duplicate key"),
        new MongoException(146, "generic aggregation memory limit"),
        new MongoException(246, "generic snapshot unavailable"),
        new MongoException(246, "TxError[12]: unrelated")}) {
      EloqDocClient client = new EloqDocClient(1000, 0);
      AtomicInteger calls = new AtomicInteger();
      try {
        client.execute(() -> {
          calls.incrementAndGet();
          throw error;
        });
        fail("Expected MongoException");
      } catch (MongoException expected) {
        assertEquals(error, expected);
      }
      assertEquals(1, calls.get());
    }
  }

  @Test
  public void stopsAtRetryTimeout() throws Exception {
    EloqDocClient client = new EloqDocClient(1, 0);
    AtomicInteger calls = new AtomicInteger();
    try {
      client.execute(() -> {
        calls.incrementAndGet();
        throw new MongoException(146, "TxError[22]: OUT_OF_MEMORY");
      });
      fail("Expected MongoException");
    } catch (MongoException expected) {
      assertEquals(146, expected.getCode());
    }
    assertEquals(true, calls.get() >= 1);
  }

  @Test
  public void rejectsUnsafeBatchSize() throws Exception {
    EloqDocClient client = new EloqDocClient();
    Properties props = new Properties();
    props.setProperty("batchsize", "10");
    client.setProperties(props);
    try {
      client.init();
      fail("Expected DBException");
    } catch (DBException expected) {
      assertEquals("EloqDoc requires batchsize=1 for safe retries",
          expected.getMessage());
    }
  }

  @Test
  public void normalMongoBindingDoesNotRetry() throws Exception {
    MongoDbClient client = new MongoDbClient();
    AtomicInteger calls = new AtomicInteger();
    try {
      client.execute(() -> {
        calls.incrementAndGet();
        throw new MongoException(146, "TxError[22]: OUT_OF_MEMORY");
      });
      fail("Expected MongoException");
    } catch (MongoException expected) {
      assertEquals(146, expected.getCode());
    }
    assertEquals(1, calls.get());
  }
}

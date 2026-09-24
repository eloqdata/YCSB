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
import site.ycsb.DBException;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** MongoDB-compatible binding with EloqDoc transaction retry semantics. */
public class EloqDocClient extends MongoDbClient {
  private long retryTimeoutMs = 600000;
  private long initialDelayMs = 30000;

  EloqDocClient(long retryTimeoutMs, long initialDelayMs) {
    this.retryTimeoutMs = retryTimeoutMs;
    this.initialDelayMs = initialDelayMs;
  }

  public EloqDocClient() {
  }

  @Override
  public void init() throws DBException {
    if (!"1".equals(getProperties().getProperty("batchsize", "1"))) {
      throw new DBException("EloqDoc requires batchsize=1 for safe retries");
    }
    try {
      retryTimeoutMs = Long.parseLong(getProperties().getProperty(
          "eloqdoc.retryTimeoutMs", "600000"));
      initialDelayMs = Long.parseLong(getProperties().getProperty(
          "eloqdoc.retryDelayMs", "30000"));
    } catch (NumberFormatException e) {
      throw new DBException("Invalid EloqDoc retry setting", e);
    }
    if (retryTimeoutMs <= 0 || initialDelayMs < 0 || initialDelayMs > 30000) {
      throw new DBException("eloqdoc.retryTimeoutMs must be positive and "
          + "eloqdoc.retryDelayMs must be between 0 and 30000");
    }
    super.init();
  }

  @Override
  protected <T> T execute(Callable<T> operation) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryTimeoutMs);
    int retries = 0;
    while (true) {
      try {
        return operation.call();
      } catch (MongoException e) {
        if (!isRetryable(e)) {
          throw e;
        }
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0) {
          throw e;
        }
        retries++;
        long delayMs = retryDelayMs(e.getCode(), retries);
        if (retries == 1 || retries % 10 == 0) {
          System.err.println("EloqDoc retry " + retries + " in " + delayMs
              + " ms after Mongo error "
              + e.getCode() + ": " + e.getMessage());
        }
        try {
          Thread.sleep(Math.min(delayMs, remainingMs));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw interrupted;
        }
        if (System.nanoTime() >= deadline) {
          throw e;
        }
      }
    }
  }

  private long retryDelayMs(int code, int retries) {
    if (initialDelayMs == 0) {
      return 0;
    }
    if (code == 146) {
      // Let checkpoint/eviction advance, and spread retrying client threads out.
      return initialDelayMs + ThreadLocalRandom.current().nextLong(initialDelayMs + 1);
    }
    long conflictDelayMs = Math.min(1000, 50L << Math.min(retries - 1, 4));
    return conflictDelayMs + ThreadLocalRandom.current().nextLong(conflictDelayMs + 1);
  }

  private static boolean isRetryable(MongoException e) {
    if (e.getCode() == 112) {
      return true;
    }
    String message = e.getMessage();
    if (message == null) {
      return false;
    }
    switch (e.getCode()) {
    case 146: // ExceededMemoryLimit: Eloq transaction out of memory
      return message.contains("TxError[22]");
    case 246: // SnapshotUnavailable: Eloq read/write conflict
      return message.contains("TxError[9]");
    default:
      return false;
    }
  }
}

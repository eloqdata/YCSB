/**
 * Copyright (c) 2012-2026 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
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

import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.commands.ProtocolCommand;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * Standalone Lavik binding: one Hash per record, no scan index, and whole-Hash
 * replacement for full-field updates. Each YCSB worker owns its connection.
 * See {@code lavik/README.md} for the workload and server requirements.
 */
public class LavikClient extends DB {
  private static final byte[] HREPLACE_BYTES =
      "LAVIK.HREPLACE".getBytes(StandardCharsets.US_ASCII);
  private static final ProtocolCommand HREPLACE = () -> HREPLACE_BYTES;

  private Jedis jedis;
  private boolean replaceAllFields;

  /** Creates a binding initialized by YCSB from workload properties. */
  public LavikClient() {
  }

  LavikClient(Jedis connection, Properties props) throws DBException {
    replaceAllFields = Boolean.parseBoolean(props.getProperty("writeallfields", "false"));
    configure(props);
    jedis = connection;
  }

  private static void configure(Properties props) throws DBException {
    if (Boolean.parseBoolean(props.getProperty("lavik.cluster", "false"))) {
      throw new DBException("Lavik binding supports standalone connections only");
    }
    try {
      double scanProportion = Double.parseDouble(props.getProperty("scanproportion", "0"));
      if (scanProportion != 0) {
        throw new DBException("Lavik binding requires scanproportion=0 (no scan index)");
      }
    } catch (NumberFormatException e) {
      throw new DBException("Invalid scanproportion", e);
    }
  }

  /** Validates the workload and opens one standalone connection for this worker. */
  @Override
  public void init() throws DBException {
    Properties props = getProperties();
    replaceAllFields = Boolean.parseBoolean(props.getProperty("writeallfields", "false"));
    configure(props);
    try {
      jedis = new Jedis(props.getProperty("lavik.host", "localhost"),
          Integer.parseInt(props.getProperty("lavik.port", Integer.toString(Protocol.DEFAULT_PORT))),
          Integer.parseInt(props.getProperty("lavik.timeout", "10000")));
      jedis.connect();
      String password = props.getProperty("lavik.password");
      if (password != null) {
        jedis.auth(password);
      }
    } catch (RuntimeException e) {
      if (jedis != null) {
        jedis.close();
      }
      throw new DBException("Opening Lavik connection failed", e);
    }
  }

  /** Closes this worker's connection. */
  @Override
  public void cleanup() {
    if (jedis != null) {
      jedis.close();
    }
  }

  /** Reads all fields with HGETALL, or the requested fields with HMGET. */
  @Override
  public Status read(String table, String key, Set<String> fields,
      Map<String, ByteIterator> result) {
    if (fields == null) {
      Map<String, String> values = jedis.hgetAll(key);
      if (values.isEmpty()) {
        return Status.NOT_FOUND;
      }
      StringByteIterator.putAllAsByteIterators(result, values);
    } else {
      String[] names = fields.toArray(new String[fields.size()]);
      List<String> values = jedis.hmget(key, names);
      for (int i = 0; i < names.length; ++i) {
        if (values.get(i) == null) {
          return Status.NOT_FOUND;
        }
        result.put(names[i], new StringByteIterator(values.get(i)));
      }
    }
    return Status.OK;
  }

  /** Inserts a Hash with HMSET, without any auxiliary index writes. */
  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    return "OK".equals(jedis.hmset(key, StringByteIterator.getStringMap(values)))
        ? Status.OK : Status.ERROR;
  }

  /** Deletes only the record key; no global scan index exists. */
  @Override
  public Status delete(String table, String key) {
    return jedis.del(key) == 0 ? Status.NOT_FOUND : Status.OK;
  }

  /**
   * Uses HSET for partial updates, or replaces the whole existing Hash when
   * writeallfields=true, preserving its expiry. A null replacement
   * server reply means NOT_FOUND. Command errors propagate; there is no HMSET
   * fallback that could silently change replacement into merge/upsert semantics.
   */
  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    Map<String, String> fields = StringByteIterator.getStringMap(values);
    if (!replaceAllFields) {
      // HSET returns the number of newly added fields. Zero is a successful
      // overwrite, not a missing record. Like Redis, this path can create a key.
      jedis.hset(key, fields);
      return Status.OK;
    }
    String[] args = new String[1 + fields.size() * 2];
    args[0] = key;
    int position = 1;
    for (Map.Entry<String, String> field : fields.entrySet()) {
      args[position++] = field.getKey();
      args[position++] = field.getValue();
    }
    jedis.getClient().sendCommand(HREPLACE, args);
    String reply = jedis.getClient().getStatusCodeReply();
    return reply == null ? Status.NOT_FOUND : "OK".equals(reply) ? Status.OK : Status.ERROR;
  }

  /** Scans are unsupported because this binding never maintains a scan index. */
  @Override
  public Status scan(String table, String startkey, int recordcount,
      Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    return Status.NOT_IMPLEMENTED;
  }
}

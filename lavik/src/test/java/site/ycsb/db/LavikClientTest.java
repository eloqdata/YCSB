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

import org.junit.Test;
import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisDataException;
import site.ycsb.ByteIterator;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Regression coverage for replacement semantics and index-free operations. */
public class LavikClientTest {
  @Test
  public void insertAndDeleteNeverWriteAnIndex() throws Exception {
    FakeJedis jedis = new FakeJedis();
    LavikClient client = new LavikClient(jedis, properties());
    assertEquals(Status.OK, client.insert("usertable", "user1", values()));
    assertEquals(1, jedis.hmsetCalls);
    assertEquals(Status.OK, client.delete("usertable", "user1"));
    assertEquals(1, jedis.delCalls);
    assertEquals(Status.NOT_IMPLEMENTED,
        client.scan("usertable", "user1", 1, null, new Vector<>()));
    // Index commands fail immediately in FakeJedis; no server is needed.
    assertEquals(0, jedis.raw.commandCalls);
  }

  @Test
  public void updateSendsAllFieldsThroughLavikExtension() throws Exception {
    FakeJedis jedis = new FakeJedis();
    LavikClient client = new LavikClient(jedis, properties());
    assertEquals(Status.OK, client.update("usertable", "user1", values()));
    assertEquals("LAVIK.HREPLACE", jedis.raw.command);
    assertEquals("user1", jedis.raw.args[0]);
    assertEquals(5, jedis.raw.args.length);
    Map<String, String> fields = new HashMap<>();
    for (int i = 1; i < jedis.raw.args.length; i += 2) {
      fields.put(jedis.raw.args[i], jedis.raw.args[i + 1]);
    }
    assertEquals(StringByteIterator.getStringMap(values()), fields);
    assertEquals(0, jedis.hmsetCalls);
  }

  @Test
  public void updateMapsMissingAndUnexpectedReplies() throws Exception {
    FakeJedis jedis = new FakeJedis();
    LavikClient client = new LavikClient(jedis, properties());
    jedis.raw.reply = null;
    assertEquals(Status.NOT_FOUND, client.update("usertable", "missing", values()));
    jedis.raw.reply = "unexpected";
    assertEquals(Status.ERROR, client.update("usertable", "user1", values()));
    assertEquals(0, jedis.hmsetCalls);
  }

  @Test
  public void serverErrorsDoNotFallBackToMerge() throws Exception {
    FakeJedis jedis = new FakeJedis();
    LavikClient client = new LavikClient(jedis, properties());
    jedis.raw.error = true;
    try {
      client.update("usertable", "user1", values());
      fail("server error swallowed");
    } catch (JedisDataException expected) {
      assertEquals(0, jedis.hmsetCalls);
      assertEquals(1, jedis.raw.commandCalls);
    }
  }

  @Test
  public void defaultAndFalseUseHsetAndAcceptZeroAddedFields() throws Exception {
    for (Properties props : Arrays.asList(new Properties(), properties())) {
      if (!props.isEmpty()) {
        props.setProperty("writeallfields", "false");
      }
      FakeJedis jedis = new FakeJedis();
      LavikClient client = new LavikClient(jedis, props);
      Map<String, ByteIterator> partial = new HashMap<>();
      partial.put("field0", new StringByteIterator("new"));
      assertEquals(Status.OK, client.update("usertable", "user1", partial));
      assertEquals(Collections.singletonMap("field0", "new"), jedis.hsetValues);
      assertEquals(1, jedis.hsetCalls);
      assertEquals(0, jedis.raw.commandCalls);
      assertEquals(0, jedis.hmsetCalls);
    }
  }

  @Test
  public void rejectsScansAndClusterBeforeConnecting() throws Exception {
    Properties props = properties();
    props.setProperty("scanproportion", "0.1");
    reject(props, "Lavik binding requires scanproportion=0 (no scan index)");
    props = properties();
    props.setProperty("lavik.cluster", "true");
    reject(props, "Lavik binding supports standalone connections only");
  }

  @Test
  public void readsAllOrSelectedFieldsAndHandlesMissingFields() throws Exception {
    FakeJedis jedis = new FakeJedis();
    LavikClient client = new LavikClient(jedis, properties());
    Map<String, ByteIterator> result = new HashMap<>();
    assertEquals(Status.OK, client.read("usertable", "user1", null, result));
    assertEquals("value0", result.get("field0").toString());
    result.clear();
    assertEquals(Status.OK,
        client.read("usertable", "user1", Collections.singleton("field0"), result));
    assertEquals(Status.NOT_FOUND,
        client.read("usertable", "user1", new HashSet<>(Arrays.asList("missing")), result));
    jedis.present = false;
    assertEquals(Status.NOT_FOUND, client.read("usertable", "missing", null, result));
    assertEquals(Status.NOT_FOUND, client.delete("usertable", "missing"));
  }

  private static void reject(Properties props, String message) throws Exception {
    LavikClient client = new LavikClient();
    client.setProperties(props);
    try {
      client.init();
      fail("invalid configuration accepted");
    } catch (DBException expected) {
      assertEquals(message, expected.getMessage());
    }
  }

  private static Properties properties() {
    Properties props = new Properties();
    props.setProperty("writeallfields", "true");
    return props;
  }

  private static Map<String, ByteIterator> values() {
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("value0"));
    values.put("field1", new StringByteIterator("value1"));
    return values;
  }

  private static final class FakeClient extends Client {
    private String command;
    private String[] args;
    private String reply = "OK";
    private boolean error;
    private int commandCalls;

    @Override
    public void sendCommand(ProtocolCommand value, String... arguments) {
      ++commandCalls;
      command = new String(value.getRaw(), StandardCharsets.US_ASCII);
      args = arguments;
    }

    @Override
    public String getStatusCodeReply() {
      if (error) {
        throw new JedisDataException("ERR unknown command");
      }
      return reply;
    }
  }

  private static final class FakeJedis extends Jedis {
    private final FakeClient raw = new FakeClient();
    private int hsetCalls;
    private Map<String, String> hsetValues;
    private int hmsetCalls;
    private int delCalls;
    private boolean present = true;

    @Override
    public Client getClient() {
      return raw;
    }

    @Override
    public String hmset(String key, Map<String, String> values) {
      ++hmsetCalls;
      return "OK";
    }

    @Override
    public Long zadd(String key, double score, String member) {
      throw new AssertionError("unexpected scan index write");
    }

    @Override
    public Long zrem(String key, String... members) {
      throw new AssertionError("unexpected scan index delete");
    }

    @Override
    public java.util.Set<String> zrangeByScore(String key, double min, double max,
        int offset, int count) {
      throw new AssertionError("unexpected scan index read");
    }

    @Override
    public Long hset(String key, Map<String, String> fields) {
      ++hsetCalls;
      hsetValues = fields;
      return 0L;
    }

    @Override
    public Long del(String key) {
      ++delCalls;
      return present ? 1L : 0L;
    }

    @Override
    public Map<String, String> hgetAll(String key) {
      return present ? Collections.singletonMap("field0", "value0") : Collections.emptyMap();
    }

    @Override
    public java.util.List<String> hmget(String key, String... fields) {
      return Collections.singletonList("field0".equals(fields[0]) ? "value0" : null);
    }
  }
}

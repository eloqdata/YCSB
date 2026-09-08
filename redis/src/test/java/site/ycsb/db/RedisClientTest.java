/**
 * Copyright (c) 2026 YCSB contributors. All rights reserved.
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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Client;
import redis.clients.jedis.commands.ProtocolCommand;
import site.ycsb.ByteIterator;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Tests the optional Redis scan-index behavior without a live server. */
public class RedisClientTest {
  @Test
  public void replacementUsesExtensionOnlyForUpdateAndReportsMissing() throws Exception {
    FakeJedis jedis = new FakeJedis();
    Properties props = properties("none");
    props.setProperty(RedisClient.UPDATE_COMMAND_PROPERTY, "keylane.hreplace");
    props.setProperty("writeallfields", "true");
    RedisClient client = new RedisClient(jedis, props);
    assertEquals(Status.OK, client.insert("usertable", "user1", values()));
    assertEquals(1, jedis.hmsetCalls);
    assertEquals(Status.OK, client.update("usertable", "user1", values()));
    assertEquals("KEYLANE.HREPLACE", jedis.raw.command);
    assertEquals("user1", jedis.raw.args[0]);
    assertEquals("field0", jedis.raw.args[1]);
    assertEquals("value0", jedis.raw.args[2]);
    assertEquals(1, jedis.hmsetCalls);
    jedis.raw.reply = null;
    assertEquals(Status.NOT_FOUND, client.update("usertable", "missing", values()));
  }

  @Test
  public void replacementRejectsPartialFieldsAndCluster() throws Exception {
    for (boolean cluster : new boolean[]{false, true}) {
      Properties props = properties("none");
      props.setProperty(RedisClient.UPDATE_COMMAND_PROPERTY, "keylane.hreplace");
      props.setProperty("writeallfields", Boolean.toString(cluster));
      props.setProperty("redis.cluster", Boolean.toString(cluster));
      try {
        new RedisClient(new FakeJedis(), props);
        fail("unsupported replacement configuration accepted");
      } catch (DBException expected) {
        assertEquals(cluster
            ? "Experimental Keylane binding requires redis.cluster=false"
            : "KEYLANE.HREPLACE binding requires writeallfields=true", expected.getMessage());
      }
    }
  }

  private static final class FakeClient extends Client {
    private String command;
    private String[] args;
    private String reply = "OK";

    @Override
    public void sendCommand(ProtocolCommand value, String... arguments) {
      command = new String(value.getRaw(), java.nio.charset.StandardCharsets.US_ASCII);
      args = arguments;
    }

    @Override
    public String getStatusCodeReply() {
      return reply;
    }
  }

  @Test
  public void noIndexModeSkipsIndexWritesAndRejectsScans() throws Exception {
    FakeJedis jedis = new FakeJedis();
    RedisClient client = new RedisClient(jedis, properties(" NONE "));

    assertEquals(Status.OK, client.insert("usertable", "user1", values()));
    assertEquals(Status.OK, client.delete("usertable", "user1"));
    assertEquals(Status.NOT_IMPLEMENTED,
        client.scan("usertable", "user1", 1, null, new Vector<>()));
    assertEquals(1, jedis.hmsetCalls);
    assertEquals(1, jedis.delCalls);
    assertEquals(0, jedis.zaddCalls);
    assertEquals(0, jedis.zremCalls);
  }

  @Test
  public void defaultModePreservesIndexWrites() throws Exception {
    FakeJedis jedis = new FakeJedis();
    RedisClient client = new RedisClient(jedis, new Properties());

    assertEquals(Status.OK, client.insert("usertable", "user1", values()));
    assertEquals(1, jedis.hmsetCalls);
    assertEquals(1, jedis.zaddCalls);
  }

  @Test
  public void invalidIndexModeFailsConfiguration() throws Exception {
    try {
      new RedisClient(new FakeJedis(), properties("unsupported"));
      fail("invalid scan index mode should fail");
    } catch (DBException expected) {
      assertEquals("Invalid redis.scanindex: unsupported; expected zset or none",
          expected.getMessage());
    }
  }

  private static Map<String, ByteIterator> values() {
    Map<String, ByteIterator> values = new HashMap<>();
    values.put("field0", new StringByteIterator("value0"));
    return values;
  }

  private static Properties properties(String scanIndex) {
    Properties props = new Properties();
    props.setProperty(RedisClient.SCAN_INDEX_PROPERTY, scanIndex);
    return props;
  }

  private static final class FakeJedis extends Jedis {
    private final FakeClient raw = new FakeClient();

    @Override
    public Client getClient() {
      return raw;
    }
    private int hmsetCalls;
    private int delCalls;
    private int zaddCalls;
    private int zremCalls;

    @Override
    public String hmset(String key, Map<String, String> values) {
      ++hmsetCalls;
      return "OK";
    }

    @Override
    public Long del(String key) {
      ++delCalls;
      return 1L;
    }

    @Override
    public Long zadd(String key, double score, String member) {
      ++zaddCalls;
      return 1L;
    }

    @Override
    public Long zrem(String key, String... members) {
      ++zremCalls;
      return 1L;
    }
  }
}

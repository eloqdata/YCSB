<!--
Copyright (c) 2014 - 2026 YCSB contributors. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License"); you
may not use this file except in compliance with the License. You
may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing
permissions and limitations under the License. See accompanying
LICENSE file.
-->

# Lavik binding

This binding benchmarks a standalone [Lavik](https://github.com/eloqdata/lavik)
server using one Jedis 3.9.0 connection per YCSB worker. Each record is a Hash.
It never creates or maintains the Redis binding's `_indices` sorted set.

| YCSB operation | Command | Behavior |
| --- | --- | --- |
| READ | `HGETALL` / `HMGET` | All / selected fields; missing data returns `NOT_FOUND` |
| INSERT | `HMSET` | Create/merge a Hash; no extra `ZADD` |
| UPDATE, `writeallfields=false` (default) | `HSET` | Merge supplied fields; create the key if absent |
| UPDATE, `writeallfields=true` | `LAVIK.HREPLACE` | Replace all fields of an existing Hash; preserve expiry; missing key returns `NOT_FOUND` |
| DELETE | `DEL` | Delete the record; no extra `ZREM` |
| SCAN | None | `NOT_IMPLEMENTED`; workloads with scans are rejected at initialization |

YCSB's standard `writeallfields` property chooses the update command. With
its default `false`, YCSB generates one random field and this binding uses
`HSET` to preserve the other fields. With `true`, YCSB generates all fields and
this binding uses `LAVIK.HREPLACE`, which removes any fields not supplied.
Unlike HSET, replacement never creates a missing key. INSERT always uses
HMSET. A successful HSET reply of zero (no newly added fields) is still `OK`.
An unsupported command or wrong-type error propagates without a fallback.
Standard Redis does not implement `LAVIK.HREPLACE`.

Like the original Redis binding, this binding uses YCSB keys directly and
ignores the `table` argument. Use a dedicated standalone DB0 dataset; different
YCSB table names do not isolate records. Cluster routing, TLS and scans are not
supported by this binding.

## Build and run

Requires a JDK (Java 8 or later), Maven 3, and a running Lavik server supporting
`LAVIK.HREPLACE`.

```sh
mvn -Psource-run -pl site.ycsb:lavik-binding -am clean package

bin/ycsb.sh load lavik -s -P workloads/workloada \
  -p lavik.host=127.0.0.1 -p lavik.port=6379 \
  -p recordcount=1000 -p writeallfields=true

bin/ycsb.sh run lavik -s -P workloads/workloada \
  -p lavik.host=127.0.0.1 -p lavik.port=6379 \
  -p recordcount=1000 -p operationcount=10000 -p writeallfields=true
```

`-Psource-run` stages the core runtime dependencies needed by `bin/ycsb.sh`
when launching from a source checkout.

The standalone archive is
`lavik/target/ycsb-lavik-binding-0.18.0-SNAPSHOT.tar.gz`; the same `bin/ycsb.sh`
commands work from its extracted root. The Python launcher `bin/ycsb` also
recognizes `lavik` when its Python runtime requirements are met.

## Properties

| Property | Default | Meaning |
| --- | --- | --- |
| `lavik.host` | `localhost` | Standalone host |
| `lavik.port` | `6379` | RESP port |
| `lavik.timeout` | `10000` | Jedis connection/socket timeout in milliseconds |
| `lavik.password` | Unset | Optional password authentication |
| `lavik.cluster` | `false` | Must remain false |
| `writeallfields` | `false` | `false`: HSET partial update; `true`: LAVIK.HREPLACE whole-Hash update |
| `scanproportion` | `0` | Must be zero; workload E is unsupported |

No scan index is maintained. The update command is selected only by
`writeallfields`; there is no separate update-command switch. Use `lavik.*`
connection properties; the `redis.*` properties belong to the separate Redis
binding.

For the record shape and operation semantics in Lavik's September 2026 YCSB
report, also set `fieldcount=10`, `fieldlength=128`,
`fieldlengthdistribution=constant`, `readallfields=true`,
`requestdistribution=uniform`, `insertorder=hashed`, and `writeallfields=true`.
Reproducing the full report additionally requires its dataset, phase-specific key ranges, thread
count, duration, and server settings; the small examples above are smoke runs.

## Origin and migration

Adapted from `thweetkomputer/YCSB` branch
[`keylane-hreplace-only`](https://github.com/thweetkomputer/YCSB/tree/a47bf01c4f197d2ff2eecbbebd9743da5a1cae47),
which contains the no-index change (`3b62b4d`) and the whole-Hash replacement
experiment (`ca221b6`). This fork adapts that behavior into `lavik/` without
changing the existing Redis binding or its Jedis 2.9.0 dependency. Jedis 3.9.0
is local to the Lavik module.

Migration: replace `redis` with `lavik` in the launcher, rename connection
properties from `redis.*` to `lavik.*`, remove `redis.scanindex` and
`redis.updatecommand`, and retain `writeallfields=true` and `scanproportion=0`.
The wire command is now `LAVIK.HREPLACE`, replacing the experiment's historical
`KEYLANE.HREPLACE` name.

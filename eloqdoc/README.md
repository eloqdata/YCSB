# EloqDoc YCSB binding

Build and load against a local EloqDoc node:

```bash
mvn -Psource-run -pl site.ycsb:eloqdoc-binding -am package -DskipTests
bin/ycsb.sh load eloqdoc -s -P workloads/workloada \
  -p mongodb.url='mongodb://127.0.0.1:27017/ycsb?w=1' \
  -p recordcount=100000 -threads 16
```

Run the same workload with `bin/ycsb.sh run eloqdoc` and the same options.
The binding supports the MongoDB binding's `mongodb.url` and `mongodb.upsert`
settings. Keep `batchsize=1` (the default); larger batches are rejected because
a partially successful bulk write cannot safely be replayed as a whole.

The binding retries EloqDoc transaction out-of-memory errors (`ExceededMemoryLimit`,
code 146, `TxError[22]`), EloqDoc read/write conflicts (`SnapshotUnavailable`,
code 246, `TxError[9]`), and MongoDB `WriteConflict` (code 112). Other errors are returned to
YCSB. OOM retries wait a random 30–60 seconds by default to give eviction and
checkpointing time to advance; conflict retries use a short jittered delay.
Each operation retries for up to ten minutes by default. Set
`eloqdoc.retryTimeoutMs` and `eloqdoc.retryDelayMs` with `-p` to change the
timeout and minimum OOM delay. A timeout returns
the last error to YCSB; it is never counted as a successful write.

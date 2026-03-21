# Raft Implementation — Distributed Collaborative Drawing Board

A real-time collaborative drawing board built on a **custom Raft consensus engine** implemented from scratch in Java. Every stroke drawn on the board is replicated from the leader node to all followers via Raft's log replication — guaranteeing that if any node crashes, the board state is never lost.

> Built from scratch. No Raft libraries. No shortcuts.

---

## What is this?

Most distributed systems tutorials stop at "here's how consensus works in theory." This project goes further — it uses a hand-rolled Raft engine as the actual backbone of a real application.

When you draw on the board:
1. The stroke is sent to the **gateway**, which routes it to the current **leader**
2. The leader appends it to its log and replicates it to all **follower** nodes
3. Once a majority confirms, the entry is **committed** — the stroke is permanent
4. If the leader crashes, a **new election completes in ~3 seconds** and the system keeps running

---

## Architecture

```
[ Browser / Frontend ]
         │
         ▼
    [ Gateway ]          ← routes all writes to current leader
         │
   ┌─────┼─────┐
   ▼     ▼     ▼
[node1] [node2] [node3]  ← Raft cluster (replica service × 3)
  :8081   :8082   :8083
```

Each replica runs the full Raft state machine:
- **Leader election** via randomized timeouts and term-based voting
- **Log replication** — leader sends AppendEntries to all followers
- **Crash recovery** — followers catch up via bulk sync on rejoin
- **Split-vote prevention** — term comparison ensures only one leader per term

---

## Benchmark Results (from real runs)

| Scenario | Result |
|---|---|
| Leader re-election time after crash | **~3 seconds** |
| Nodes in cluster | 3 |
| Quorum required to commit | 2 / 3 nodes |
| Replication consistency | 3/3 nodes on every commit |
| Log entries replicated | 150+ across leader changes |
| Leader elections observed | 2 (node2 → node3) |
| Follower catch-up on rejoin | Full log sync confirmed |
| Node startup time | ~9–10 seconds (Spring Boot) |

---

## What the logs show

**Leader election (term 1):**
```
[ELECTION] Node node2 starting election for term 1 peers: [replica1:8081, replica3:8083]
[ELECTION] Got vote from replica1:8081
[ELECTION] Got vote from replica3:8083
[ELECTION] Node node2 got 3/3 votes, majority needed: 2
[ELECTION] NODE BECAME LEADER: node2 term: 1
```

**Log replication to followers:**
```
[REPLICATED] Node node1 appended 1 entries from leader: node2. Log size now: 77
[REPLICATED] Node node3 appended 1 entries from leader: node2. Log size now: 77
[COMMITTED]  Leader node2 committed entry at index 77. Replicated to 3/3 nodes.
```

**Follower catch-up after crash:**
```
[CATCHUP] Peer replica1:8081 is now caught up to index 74
[CATCHUP] Peer replica3:8083 is now caught up to index 74
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Consensus engine | Java (from scratch) |
| Backend / Replicas | Spring Boot, REST APIs |
| Gateway | Spring Boot |
| Frontend | HTML |
| Containerization | Docker |
| Inter-node communication | HTTP (AppendEntries, RequestVote RPCs) |

---

## How to Run

### Prerequisites
- Docker installed
- Ports 8081, 8082, 8083 free

### Start the cluster

```bash
# Build and start all 3 replica nodes + gateway
docker start replica1 replica2 replica3
docker start gateway
```

### Watch the election happen
```bash
docker logs replica2 --tail 30
```

### Simulate a leader crash
```bash
docker stop replica2
# Watch replica1 or replica3 become the new leader within ~3 seconds
docker logs replica1 --tail 30
```

### Restart the crashed node and watch catch-up
```bash
docker start replica2
docker logs replica2 --tail 30
# You'll see [CATCHUP] entries as it syncs the full log
```

---

## Key Implementation Details

**Leader Election**
- Each node starts with a randomized election timeout to avoid split votes
- A node becomes candidate, increments its term, and requests votes from peers
- First node to reach majority (2/3) wins and begins sending heartbeats

**Log Replication**
- Every draw stroke is an entry in the Raft log
- Leader sends AppendEntries RPC to all followers on every write
- Entry is committed only after majority acknowledgment
- Committed entries are applied to the state machine (drawing board state)

**Crash Recovery**
- On leader crash, remaining nodes detect missing heartbeats and trigger election
- Re-election completes in ~3 seconds
- When crashed node restarts, it receives all missed entries via catch-up sync
- Strong consistency guaranteed — no entries are lost or duplicated

---

## Project Structure

```
Raft-Implementation/
├── frontend/
│   ├── index.html          # Drawing board UI
│   ├── canvas.js           # Canvas rendering logic
│   └── websocket.js        # WebSocket client — connects to gateway
│
├── gateway/
│   └── src/main/java/com/draw/gateway/
│       ├── model/
│       │   └── StrokeEvent.java
│       ├── service/        # Leader discovery + request routing
│       ├── websocket/
│       │   ├── DrawingSocketHandler.java   # Receives strokes from browser
│       │   └── WebSocketConfig.java
│       └── GatewayApplication.java
│
└── replica/
    └── src/main/java/com/draw/replica/
        ├── controller/
        │   ├── AppendEntriesController.java  # Handles log replication RPCs
        │   ├── LeaderController.java         # Accepts writes from gateway
        │   ├── SyncController.java           # Catch-up sync for rejoining nodes
        │   └── VoteController.java           # Handles RequestVote RPCs
        ├── model/
        │   ├── AppendEntriesRequest/Response.java
        │   ├── LogEntry.java                 # Single entry in the Raft log
        │   ├── StrokeEvent.java              # Drawing stroke (the actual data)
        │   ├── VoteRequest/Response.java
        ├── raft/
        │   ├── RaftNode.java                 # Core state machine (FOLLOWER/CANDIDATE/LEADER)
        │   ├── ElectionService.java          # Leader election + voting
        │   ├── HeartbeatService.java         # Leader heartbeats to prevent re-election
        │   ├── ReplicationService.java       # AppendEntries to all followers
        │   └── CommitService.java            # Commits entries after majority ack
        └── ReplicaApplication.java
```

---

## Why this project

Raft is one of the most important algorithms in distributed systems — it powers etcd, CockroachDB, TiKV, and Consul. Most engineers use it through a library. Building it from scratch forces you to understand every edge case: split votes, log conflicts, term mismatches, and network partitions.

Adding a real application (the drawing board) on top means consistency isn't just a theoretical property — you can *see* it working in real time.

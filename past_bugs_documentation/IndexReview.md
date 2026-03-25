# Index & Ordering Audit — March 2026

Full audit of batch isolation, agent index correctness, and masking consistency
across the Python training stack and Java bridging code.

---

## 1. `observations.py` — Double-Indexing Bug (FIXED)

**Severity:** High (latent — masked by HashMap iteration order coincidence)

### What was wrong

Every field assignment in `VectorizedObservations._fill()` applied `[ai]` to both
sides of the assignment:

```python
ai = self.agent_indices           # e.g., [2, 0, 1] if Java sent agent 2 first
self.positions[ai] = torch.from_numpy(raw_data[:, 1:4]).float()[ai]  # BUG
```

The right-hand `[ai]` re-indexes the wire-order data *by the agent indices themselves*,
scrambling the result. If wire order is `[agent2_data, agent0_data, agent1_data]`
and `ai = [2, 0, 1]`, then `tensor[ai]` gives `[tensor[2], tensor[0], tensor[1]]`
= `[agent1_data, agent2_data, agent0_data]`. Writing that back via
`self.positions[[2, 0, 1]] = [agent1_data, agent2_data, agent0_data]` puts agent 1's
position into slot 2, etc. — all slots get the wrong agent's data.

The same pattern repeated for all scalar fields: `group_indices`, `team_indices`,
`damage_dealt`, `damage_taken`, `kills`, `deaths`, `num_bullets`, `health`.

### Why it didn't manifest

Java builds observations in a `HashMap<Integer, AgentObservation>` and iterates it
in `VectorizedObservationEncoder`. Java's HashMap with sequential integer keys 0..N-1
resizes to capacity N (next power of 2), at which point `key % capacity == key`, so
bucket iteration yields sequential order. The double-indexing was a no-op when
`ai = [0, 1, 2, ..., N-1]`. This is an implementation detail of Java's HashMap —
not a contract.

### Fix

Remove `[ai]` from the right-hand side. The left-hand `[ai]` scatter is the correct
and only indexing needed:

```python
self.positions[ai] = torch.from_numpy(raw_data[:, 1:4]).float()
```

---

## 2. `binary_transport.py` — Action Encoding Ordered by Wire Position, Not Agent ID (FIXED)

**Severity:** High (same root cause as #1)

### What was wrong

In `_encode_actions`, actions were sliced using `active_mask` (a boolean over
wire-order positions) rather than indexed by actual agent IDs:

```python
angles_active = angles[active_mask]   # BUG: selects by wire position
```

`angles[i]` is the model output for batch slot `i` = absolute agent index `i`.
`active_mask` is a boolean of shape `[agent_count]` (wire order). If wire order is
`[2, 0, 1]`, then `angles[active_mask]` = `[angles[0], angles[1], angles[2]]`
(positions 0, 1, 2 in the output tensor), but the packet labels them as agents
`[2, 0, 1]`. Agent 2 receives agent 0's action; agent 0 receives agent 1's action, etc.

### Fix

Index actions by the actual agent IDs extracted from the observation packet:

```python
active_ids = agent_indices[active_mask]   # long tensor of true agent indices
angles_active = angles[active_ids]        # correct: slot k gets action for agent k
```

---

## 3. `train_vectorized.py` — Double-Mask in `calculate_rewards` (FIXED)

**Severity:** Low (redundant, harmless while all agents are active)

### What was wrong

`positions` was already filtered by `active_mask` on line 140, then filtered again
on line 149:

```python
positions = obs.positions[active_mask]          # already [active_count, 3]
distances = torch.norm(positions[active_mask] - target_position, dim=1)  # [ai] again
```

Applying `active_mask` a second time to an already-filtered tensor is redundant
when all agents are active (`active_mask` all-True). If ever some agents were
inactive, this would index out of bounds.

### Fix

```python
distances = torch.norm(positions - target_position, dim=1)
```

---

## 4. `train_vectorized.py` — Force-Clone on Death Uses Last-Tick Deaths Only

**Severity:** Medium (design issue, disabled by flag)

### What was wrong

```python
will_clone[obs.deaths > 0] = True  # Force clone if agent died this round
```

`obs.deaths` is populated from `rlOp.getDeathsLastTick()` — deaths that occurred on
the **single tick** the observation was sampled. `apply_fmc_update` runs every 10
ticks. Deaths on ticks 1–9 (relative to the last FMC update) are invisible to this
check, so only ~10% of death events ever trigger force-cloning.

Additionally, letting the standard FMC algorithm handle fitness (including death
penalty in the reward signal) is cleaner than a hard override.

### Resolution

Added `FORCE_CLONE_UPON_DEATH = False` constant at the top of `train_vectorized.py`
(with comment noting it is not recommended). The force-clone line is now gated on
this flag.

---

## 5. `batched_linear.py` — `_extract_from_and_to` Naming is Backwards (unfixed, naming only)

**Severity:** Low (clarity / future maintenance risk)

### What is confusing

```python
clone_from = torch.arange(batch_size)[clone_mask]   # actually the DESTINATION (losers)
clone_to   = clone_indices[clone_mask]               # actually the SOURCE (winners)
```

The names say the opposite of what they mean: `clone_from` is the slot being
overwritten; `clone_to` is the slot being read. The assignment
`self.weight[clone_from] = self.weight[clone_to]` happens to be correct because
the names are *consistently* backwards. The same convention is used in `blend`.

Not fixed to avoid churn, but worth a rename pass in the future.

---

## 6. Java — `getIndexForRLOperator` Index Shift if `allowRespawns = false` (latent)

**Severity:** Low (latent, currently unreachable)

### What could go wrong

`getIndexForRLOperator` iterates `operatorsArray` and counts only non-null
RLOperator entries. If an operator dies and `operatorsArray[k]` is nulled out
(which only happens when `allowRespawns = false`), all surviving operators at
positions `j > k` have their sequential index decremented by 1. Java would apply
actions to the wrong operators.

`allowRespawns` is currently hardcoded `true` in `TrainingState`, so this never
triggers. If it is ever set to `false`, this must be fixed before use.

---

## Java HashMap Ordering — Root Cause of Bugs #1 and #2

Java's `HashMap` with small sequential integer keys (0..N-1) happens to iterate in
insertion/key order because each key hashes to its own bucket after the map resizes.
This means Java *currently* always sends observations in agent-index order, making
bugs #1 and #2 silent. Any of the following would expose them:

- Agent count large enough to cause hash collisions before resize
- Java version change altering HashMap internals
- Deliberate reordering (e.g., sending highest-reward agents first)

A safe long-term fix on the Java side would be to switch to a `LinkedHashMap` or
sort observations by key before encoding, guaranteeing sequential wire order.

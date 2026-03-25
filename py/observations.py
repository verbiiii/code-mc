import torch
import numpy as np
import struct


class VectorizedObservations:
    def __init__(self, binary_data: bytes, num_agents: int):
        self.tick = 0
        self.num_agents = int(num_agents)
        self.positions = torch.zeros((num_agents, 3), dtype=torch.float32)
        self.group_indices = torch.zeros(num_agents, dtype=torch.int64)
        self.team_indices = torch.zeros(num_agents, dtype=torch.int64)
        self.damage_dealt = torch.zeros(num_agents, dtype=torch.float32)
        self.damage_taken = torch.zeros(num_agents, dtype=torch.float32)
        self.kills = torch.zeros(num_agents, dtype=torch.float32)
        self.deaths = torch.zeros(num_agents, dtype=torch.float32)
        self.num_bullets = torch.zeros(num_agents, dtype=torch.float32)
        self.health = torch.zeros(num_agents, dtype=torch.float32)
        self.is_alive = torch.zeros(num_agents, dtype=torch.bool)
        self.rewards = torch.zeros(num_agents, dtype=torch.float32)
        self._fill(binary_data)

    def _fill(self, binary_data: bytes):
        if len(binary_data) < 16:
            raise ValueError("Binary data too short for header")
        
        # Parse header
        magic, tick, agent_count, obs_size = struct.unpack('<IIII', binary_data[:16])
        self.tick = int(tick)
        if magic != 0xFEEDBEEF:
            raise ValueError(f"Invalid magic number: {magic:#010x}")
        
        if agent_count != self.positions.shape[0]:
            raise ValueError(f"Agent count mismatch: expected {self.positions.shape[0]}, got {agent_count}")
        
        if len(binary_data) != 16 + agent_count * obs_size * 4:
            raise ValueError("Binary data size does not match expected size")
        
        # Parse observation data
        raw_data = np.frombuffer(binary_data[16:], dtype='<f4').reshape(agent_count, obs_size)
        self.agent_indices = torch.from_numpy(raw_data[:, 0].astype(np.int64))

        wire_agent_ids = self.agent_indices
        if wire_agent_ids.shape[0] != self.num_agents:
            raise ValueError(f"agent_indices shape mismatch: expected ({self.num_agents},), got {tuple(wire_agent_ids.shape)}")

        if (wire_agent_ids < 0).any() or (wire_agent_ids >= self.num_agents).any():
            bad = wire_agent_ids[(wire_agent_ids < 0) | (wire_agent_ids >= self.num_agents)]
            raise ValueError(f"agent_indices out of range [0, {self.num_agents}): {bad.tolist()}")

        unique_count = int(torch.unique(wire_agent_ids).numel())
        if unique_count != self.num_agents:
            raise ValueError(f"agent_indices must be a permutation of 0..{self.num_agents - 1}, got {unique_count} unique ids")

        # Scatter from wire-order rows into stable absolute-agent slots.
        agent_ids = wire_agent_ids
        self.positions[agent_ids] = torch.from_numpy(raw_data[:, 1:4]).float()
        self.group_indices[agent_ids] = torch.from_numpy(raw_data[:, 4].astype(np.int64))
        self.team_indices[agent_ids] = torch.from_numpy(raw_data[:, 5].astype(np.int64))
        self.damage_dealt[agent_ids] = torch.from_numpy(raw_data[:, 6])
        self.damage_taken[agent_ids] = torch.from_numpy(raw_data[:, 7])
        self.kills[agent_ids] = torch.from_numpy(raw_data[:, 8])
        self.deaths[agent_ids] = torch.from_numpy(raw_data[:, 9])
        self.num_bullets[agent_ids] = torch.from_numpy(raw_data[:, 10])
        self.health[agent_ids] = torch.from_numpy(raw_data[:, 11]) if obs_size > 11 else torch.zeros(agent_count, dtype=torch.float32)

    def tensorized(self) -> torch.Tensor:
        return torch.cat((
            # all zeros entry to fill later (one-hot encoding)
            torch.zeros((self.positions.shape[0], 1), dtype=torch.float32),
            self.rewards.unsqueeze(1).float(),
            self.positions,
            self.group_indices.unsqueeze(1).float(),
            self.team_indices.unsqueeze(1).float(),
            self.damage_dealt.unsqueeze(1),
            self.damage_taken.unsqueeze(1),
            self.kills.unsqueeze(1),
            self.deaths.unsqueeze(1),
            self.num_bullets.unsqueeze(1),
            self.health.unsqueeze(1),
        ), dim=1)
import torch
import numpy as np
import struct


class VectorizedObservations:
    def __init__(self, binary_data: bytes, num_agents: int):
        self.positions = torch.zeros((num_agents, 3), dtype=torch.float32)
        self.group_indices = torch.zeros(num_agents, dtype=torch.int64)
        self.team_indices = torch.zeros(num_agents, dtype=torch.int64)
        self.damage_dealt = torch.zeros(num_agents, dtype=torch.float32)
        self.damage_taken = torch.zeros(num_agents, dtype=torch.float32)
        self.kills = torch.zeros(num_agents, dtype=torch.float32)
        self.deaths = torch.zeros(num_agents, dtype=torch.float32)
        self.num_bullets = torch.zeros(num_agents, dtype=torch.float32)
        self.is_alive = torch.zeros(num_agents, dtype=torch.bool)
        self._fill(binary_data)

    def _fill(self, binary_data: bytes):
        if len(binary_data) < 16:
            raise ValueError("Binary data too short for header")
        
        # Parse header
        magic, tick, agent_count, obs_size = struct.unpack('<IIII', binary_data[:16])
        if magic != 0xFEEDBEEF:
            raise ValueError(f"Invalid magic number: {magic:#010x}")
        
        if agent_count != self.positions.shape[0]:
            raise ValueError(f"Agent count mismatch: expected {self.positions.shape[0]}, got {agent_count}")
        
        if len(binary_data) != 16 + agent_count * obs_size * 4:
            raise ValueError("Binary data size does not match expected size")
        
        # Parse observation data
        raw_data = np.frombuffer(binary_data[16:], dtype='<f4').reshape(agent_count, obs_size)
        self.agent_indices = torch.from_numpy(raw_data[:, 0].astype(np.int64))
        ai = self.agent_indices
        self.positions[ai] = torch.from_numpy(raw_data[:, 1:4]).float()[ai]
        self.group_indices[ai] = torch.from_numpy(raw_data[:, 4].astype(np.int64))[ai]
        self.team_indices[ai] = torch.from_numpy(raw_data[:, 5].astype(np.int64))[ai]
        self.damage_dealt[ai] = torch.from_numpy(raw_data[:, 6])[ai]
        self.damage_taken[ai] = torch.from_numpy(raw_data[:, 7])[ai]
        self.kills[ai] = torch.from_numpy(raw_data[:, 8])[ai]
        self.deaths[ai] = torch.from_numpy(raw_data[:, 9])[ai]
        self.num_bullets[ai] = torch.from_numpy(raw_data[:, 10])[ai]
        self.health[ai] = torch.from_numpy(raw_data[:, 11])[ai] if obs_size > 11 else torch.zeros(agent_count, dtype=torch.float32)
        self.is_alive[ai] = self.health[ai] > 0.0

    def tensorized(self) -> torch.Tensor:
        return torch.cat((
            self.positions,
            self.group_indices.unsqueeze(1).float(),
            self.team_indices.unsqueeze(1).float(),
            self.damage_dealt.unsqueeze(1),
            self.damage_taken.unsqueeze(1),
            self.kills.unsqueeze(1),
            self.deaths.unsqueeze(1),
            self.num_bullets.unsqueeze(1),
            self.health.unsqueeze(1),
            self.is_alive.unsqueeze(1).float()
        ), dim=1)
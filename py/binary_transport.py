#!/usr/bin/env python3
"""
Ultra-fast binary transport layer for Java <-> Python communication.
Handles all parsing, encoding, and protocol logic.
"""

import struct
import time
import numpy as np
import torch
from typing import Optional, Tuple, Dict

from observations import VectorizedObservations
from train_vectorized import VectorizedTrainer


class BinaryTransport:
    """Handles binary protocol communication and observation/action encoding."""
    
    def __init__(self, trainer: VectorizedTrainer):
        self.trainer = trainer
        self.tick_count = 0
        self.expected_next_incoming_tick = None
        self.total_skipped_steps = 0
        
        # Performance monitoring
        self.processing_times = []
        
        print("🚀 BinaryTransport initialized")

    def process_observations(self, binary_data: bytes) -> bytes:
        """
        Process incoming binary observation data and return actions.
        
        Binary format:
        - Header: 16 bytes (magic(4) + tick(4) + agent_count(4) + obs_size(4))
        - Data: Direct numpy array bytes (agent_count * obs_size * 4 bytes for float32)
        
        Returns binary action data in same format.
        """
        start_time = time.perf_counter()
        
        # Forward pass through model
        obs = VectorizedObservations(binary_data, self.trainer.num_agents)
        if self.expected_next_incoming_tick is None:
            self.expected_next_incoming_tick = obs.tick + 1
            steps_behind = 0
        else:
            steps_behind = max(0, obs.tick - self.expected_next_incoming_tick)
            self.expected_next_incoming_tick = obs.tick + 1
        self.total_skipped_steps += steps_behind

        move_w_actions, move_a_actions, move_s_actions, move_d_actions, shoot_actions, jump_actions, sneak_actions, pitch_actions, yaw_actions = self.trainer.tick(obs)
        self.tick_count += 1

        # Convert WASD to angle+walk for wire compatibility with current Java decoder.
        forward = move_w_actions.float() - move_s_actions.float()
        strafe = move_d_actions.float() - move_a_actions.float()
        walk_actions = (forward != 0.0) | (strafe != 0.0)
        angles = torch.rad2deg(torch.atan2(strafe, forward))
        pitch_degrees = (pitch_actions.float() / 8.0) * 180.0 - 90.0  # Map 0-7 to -90 to +90 degrees
        yaw_degrees = (yaw_actions.float() / 8.0) * 360.0  # Map 0-7 to 0 to 360 degrees
        actions_binary = self._encode_actions(obs.agent_indices, angles, walk_actions, shoot_actions, jump_actions, sneak_actions, pitch_degrees, yaw_degrees)

        # Performance tracking
        processing_time = (time.perf_counter() - start_time) * 1000
        self.processing_times.append(processing_time)
        if len(self.processing_times) > 100:
            self.processing_times.pop(0)

        active_agents = int((obs.agent_indices != -1).sum().item())
        self.trainer.update_runtime_status(
            processing_time_ms=processing_time,
            active_agents=active_agents,
            steps_behind=steps_behind,
            total_skipped_steps=self.total_skipped_steps,
        )
            
        return actions_binary

    def _encode_actions(self, agent_indices: torch.Tensor, angles: torch.Tensor, 
                       walk: torch.Tensor, shoot: torch.Tensor, jump: torch.Tensor, sneak: torch.Tensor, pitch: torch.Tensor, yaw: torch.Tensor) -> bytes:
        """Encode actions into ultra-compact binary format - only for active agents."""
        # Filter out inactive agents (agent_indices == -1)
        active_mask = agent_indices != -1
        active_count = active_mask.sum().item()

        if active_count == 0:
            return self._encode_empty_actions()

        # Use actual agent IDs to index actions — NOT wire position.
        # angles[i] is the model output for batch slot i (absolute agent index i),
        # so we must index by agent ID, not by position in the packet.
        active_ids = agent_indices[active_mask]
        agent_indices_masked = active_ids.cpu().numpy().astype(np.float32)

        # Get actions only for active agents, indexed by their absolute agent IDs
        # (If model outputs are on GPU, indices must be on the same device.)
        active_ids_dev = active_ids.to(angles.device)
        angles_active = angles[active_ids_dev]
        walk_active = walk[active_ids_dev]
        shoot_active = shoot[active_ids_dev]
        jump_active = jump[active_ids_dev]
        sneak_active = sneak[active_ids_dev]
        pitch_active = pitch[active_ids_dev]
        yaw_active = yaw[active_ids_dev]
        
        # Convert to numpy arrays (pure vectorized) - NO AGENT INDICES
        angles_np = angles_active.cpu().numpy().astype(np.float32)
        walk_np = walk_active.cpu().numpy().astype(np.float32)
        shoot_np = shoot_active.cpu().numpy().astype(np.float32)
        jump_np = jump_active.cpu().numpy().astype(np.float32)
        sneak_np = sneak_active.cpu().numpy().astype(np.float32)
        pitch_np = pitch_active.cpu().numpy().astype(np.float32)
        yaw_np = yaw_active.cpu().numpy().astype(np.float32)
        
        # Stack into action array [N, 7] - [angle, walk, shoot, jump, sneak, pitch, yaw]
        action_array = np.column_stack([agent_indices_masked, angles_np, walk_np, shoot_np, jump_np, sneak_np, pitch_np, yaw_np])
        
        # Convert to little-endian bytes
        action_bytes = action_array.astype('<f4').tobytes()
        
        # Create header: magic(4) + count(4) + tick(4) + action_size(4)
        header = struct.pack('<IIII', 0xACE5BEEF, active_count, self.tick_count, action_array.shape[1])
        
        return header + action_bytes

    def _encode_empty_actions(self) -> bytes:
        """Encode empty action response."""
        return struct.pack('<IIII', 0xACE5BEEF, 0, self.tick_count, 7)

    def end_round(self):
        """Signal end of round for learning updates."""
        self.trainer.on_round_end()

    def get_performance_stats(self) -> Dict:
        """Get performance and training statistics."""
        stats = self.trainer.get_stats()
        
        if self.processing_times:
            stats["avg_processing_ms"] = np.mean(self.processing_times)
            stats["max_processing_ms"] = np.max(self.processing_times)
            
        return stats
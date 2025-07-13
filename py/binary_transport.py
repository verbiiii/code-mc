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

from train_vectorized import VectorizedTrainer


class BinaryTransport:
    """Handles binary protocol communication and observation/action encoding."""
    
    def __init__(self, trainer: VectorizedTrainer):
        self.trainer = trainer
        self.tick_count = 0
        
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
        
        try:
            # Parse and validate header
            obs_tensor, agent_indices, rewards = self._parse_observations(binary_data)
            if obs_tensor is None:
                return self._encode_empty_actions()
            
            # Forward pass through model
            x_actions, y_actions, walk_actions, shoot_actions, log_probs = self.trainer.forward_pass(obs_tensor)
            
            # Update training data
            self.trainer.update_episode_data(agent_indices, rewards, log_probs)
            
            # Convert actions and encode response
            angles = (x_actions.float() / 8.0) * 360.0
            actions_binary = self._encode_actions(agent_indices, angles, walk_actions, shoot_actions)
            
            # Performance tracking
            processing_time = (time.perf_counter() - start_time) * 1000
            self.processing_times.append(processing_time)
            if len(self.processing_times) > 100:
                self.processing_times.pop(0)
                
            if processing_time > 1.0:  # Warn if >1ms
                print(f"⚠️ Slow processing: {processing_time:.2f}ms")
                
            return actions_binary
            
        except Exception as e:
            print(f"🚨 Error processing observations: {e}")
            return self._encode_empty_actions()

    def _parse_observations(self, binary_data: bytes) -> Tuple[Optional[torch.Tensor], Optional[torch.Tensor], Optional[torch.Tensor]]:
        """Parse binary observation data into tensors."""
        if len(binary_data) < 16:
            print(f"⚠️ Invalid data size: {len(binary_data)} < 16")
            return None, None, None
            
        # Parse header
        magic, tick, agent_count, obs_size = struct.unpack('>IIII', binary_data[:16])
        
        if magic != 0xFEEDBEEF:
            print(f"⚠️ Invalid magic: 0x{magic:08X}")
            return None, None, None
            
        self.tick_count = tick
        
        # Validate data size
        expected_data_size = agent_count * obs_size * 4  # float32 = 4 bytes
        if len(binary_data) != 16 + expected_data_size:
            print(f"⚠️ Size mismatch: got {len(binary_data)}, expected {16 + expected_data_size}")
            return None, None, None
        
        # Direct numpy interpretation - ZERO PYTHON LOOPS
        raw_data = binary_data[16:]
        obs_array = np.frombuffer(raw_data, dtype='>f4').reshape(agent_count, obs_size)
        
        # Convert to torch tensor
        obs_tensor = torch.from_numpy(obs_array.copy()).to(self.trainer.device)  # [N, obs_size]
        
        # Extract components vectorized
        # Format: [agent_idx, my_x, my_y, my_z, opp_x, opp_y, opp_z, dmg_dealt, dmg_taken, kills, deaths]
        agent_indices = obs_tensor[:, 0].long()  # [N]
        positions = obs_tensor[:, 1:7]          # [N, 6] - my_pos + opp_pos  
        reward_data = obs_tensor[:, 7:11]       # [N, 4] - damage/kill data
        
        # Validate agent indices
        valid_mask = (agent_indices >= 0) & (agent_indices < self.trainer.max_agents)
        if not valid_mask.any():
            print("⚠️ No valid agent indices")
            return None, None, None
            
        # Filter to valid agents
        valid_indices = agent_indices[valid_mask]
        valid_positions = positions[valid_mask]
        valid_rewards = reward_data[valid_mask]
        
        # Calculate rewards vectorized: dmg_dealt - 0.1*dmg_taken + 100*kills
        rewards = valid_rewards[:, 0] - 0.1 * valid_rewards[:, 1] + 100.0 * valid_rewards[:, 2]
        
        return valid_positions, valid_indices, rewards

    def _encode_actions(self, agent_indices: torch.Tensor, angles: torch.Tensor, 
                       walk: torch.Tensor, shoot: torch.Tensor) -> bytes:
        """Encode actions into ultra-compact binary format."""
        num_actions = len(agent_indices)
        
        # Convert to numpy arrays (pure vectorized)
        indices_np = agent_indices.cpu().numpy().astype(np.float32)
        angles_np = angles.cpu().numpy().astype(np.float32)
        walk_np = walk.cpu().numpy().astype(np.float32)
        shoot_np = shoot.cpu().numpy().astype(np.float32)
        
        # Stack into action array [N, 4] - no loops!
        action_array = np.column_stack([indices_np, angles_np, walk_np, shoot_np])
        
        # Convert to big-endian bytes
        action_bytes = action_array.astype('>f4').tobytes()
        
        # Create header: magic(4) + count(4) + tick(4) + action_size(4)
        header = struct.pack('>IIII', 0xACE5BEEF, num_actions, self.tick_count, 4)
        
        return header + action_bytes

    def _encode_empty_actions(self) -> bytes:
        """Encode empty action response."""
        return struct.pack('>IIII', 0xACE5BEEF, 0, self.tick_count, 4)

    def end_round(self):
        """Signal end of round for learning updates."""
        self.trainer.apply_reinforce_update(torch.arange(self.trainer.max_agents, device=self.trainer.device))
        print(f"🏁 Round complete - applied learning updates")

    def get_performance_stats(self) -> Dict:
        """Get performance and training statistics."""
        stats = self.trainer.get_stats()
        
        if self.processing_times:
            stats["avg_processing_ms"] = np.mean(self.processing_times)
            stats["max_processing_ms"] = np.max(self.processing_times)
            
        return stats


# Global instances
transport = None
trainer = None

def initialize_transport(max_agents=128, device='cpu'):
    """Initialize the transport layer and trainer."""
    global transport, trainer
    
    trainer = VectorizedTrainer(max_agents=max_agents, device=device)
    transport = BinaryTransport(trainer)
    
    print(f"✅ Binary transport ready for {max_agents} agents on {device}")

def process_binary_data(binary_data: bytes) -> bytes:
    """Main entry point for binary data processing."""
    if transport is None:
        print("⚠️ Transport not initialized!")
        return struct.pack('>IIII', 0xACE5BEEF, 0, 0, 4)
        
    return transport.process_observations(binary_data)

def signal_round_end():
    """Signal end of round."""
    if transport is not None:
        transport.end_round()

def get_stats() -> Dict:
    """Get performance statistics."""
    if transport is not None:
        return transport.get_performance_stats()
    return {}

# Auto-initialize if run directly
if __name__ == "__main__":
    initialize_transport(128)
    print("🚀 Binary transport ready!")

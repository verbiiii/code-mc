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

from train_vectorized import VectorizedTrainer, MAX_AGENTS


class BinaryTransport:
    """Handles binary protocol communication and observation/action encoding."""
    
    def __init__(self, trainer: VectorizedTrainer):
        self.trainer = trainer
        self.tick_count = 0
        
        # Performance monitoring
        self.processing_times = []
        
        # Agent ID mapping: large entity IDs -> small indices (0-63)
        self.agent_id_to_index = {}
        self.next_index = 0
        
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
            obs_tensor, agent_indices, reward_data = self._parse_observations(binary_data)
            if obs_tensor is None:
                return self._encode_empty_actions()
            
            # Forward pass through model
            x_actions, y_actions, walk_actions, shoot_actions, log_probs = self.trainer.forward_pass(obs_tensor)
            
            # Log agent activity for play mode (every 20 ticks to avoid spam)
            if hasattr(self, 'tick_count') and self.tick_count % 20 == 0:
                active_mask = agent_indices != -1
                active_count = active_mask.sum().item()
                if active_count > 0:
                    active_agent_indices = agent_indices[active_mask]
                    cumulative_rewards = self.trainer.cumulative_rewards
                    
                    # Show current champion info occasionally
                    if hasattr(self.trainer, 'best_agent_idx') and self.tick_count % 100 == 0:
                        best_idx = self.trainer.best_agent_idx
                        best_reward = self.trainer.best_agents_ever[best_idx].item()
                        print(f"👑 ALL-TIME CHAMPION: Agent {best_idx} (reward: {best_reward:.2f})")
                    
                    # Show which agent indices are active and their current rewards
                    for i, agent_idx in enumerate(active_agent_indices):
                        agent_idx_val = agent_idx.item()
                        if agent_idx_val >= 0 and agent_idx_val < len(cumulative_rewards):
                            reward = cumulative_rewards[agent_idx_val].item()
                            champion_indicator = "👑" if hasattr(self.trainer, 'best_agent_idx') and agent_idx_val == self.trainer.best_agent_idx else ""
                            print(f"🎮 Active agent {agent_idx_val}: reward={reward:.2f} {champion_indicator}")
            
            # Update training data
            self.trainer.update_episode_data(agent_indices, reward_data, log_probs)

            # Convert actions and encode response
            angles = (x_actions.float() / 8.0) * 360.0
            actions_binary = self._encode_actions(agent_indices, angles, walk_actions, shoot_actions)
            
            # Performance tracking
            processing_time = (time.perf_counter() - start_time) * 1000
            self.processing_times.append(processing_time)
            if len(self.processing_times) > 100:
                self.processing_times.pop(0)
                
            if processing_time > 5.0:  # Only warn if >5ms (reduced noise)
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
        magic, tick, agent_count, obs_size = struct.unpack('<IIII', binary_data[:16])
        
        if magic != 0xFEEDBEEF:
            print(f"⚠️ Invalid magic: 0x{magic:08X}")
            return None, None, None
            
        # Debug: Alert if we're getting too many agents (reduced verbosity)
        if agent_count > 64:
            print(f"🚨 ALERT: Received {agent_count} agents! Java cleanup issue detected.")
            
        self.tick_count = tick
        
        # Validate data size
        expected_data_size = agent_count * obs_size * 4  # float32 = 4 bytes
        if len(binary_data) != 16 + expected_data_size:
            print(f"⚠️ Size mismatch: got {len(binary_data)}, expected {16 + expected_data_size}")
            return None, None, None
        
        # Direct numpy interpretation - ZERO PYTHON LOOPS
        raw_data = binary_data[16:]
        obs_array = np.frombuffer(raw_data, dtype='<f4').reshape(agent_count, obs_size)
        
        # Convert to torch tensor
        obs_tensor = torch.from_numpy(obs_array.copy()).to(self.trainer.device)  # [N, obs_size]
        
        # Extract components vectorized - NOW WITH AGENT INDICES
        # Format: [agent_index, my_x, my_y, my_z, opp_x, opp_y, opp_z, dmg_dealt, dmg_taken, kills, deaths]
        raw_agent_ids = obs_tensor[:, 0].long()  # Raw agent IDs from data (can be large)
        positions = obs_tensor[:, 1:7]           # [N, 6] - my_pos + opp_pos  
        reward_data = obs_tensor[:, 7:11]        # [N, 4] - damage/kill data
        
        # Map large agent IDs to small indices (0-63)
        mapped_indices = torch.zeros_like(raw_agent_ids)
        skipped_count = 0
        
        # If we're getting way too many agents, force a reset
        if agent_count > 128:
            print(f"🔥 EMERGENCY RESET: Too many agents ({agent_count}), forcing agent mapping reset")
            self.agent_id_to_index.clear()
            self.next_index = 0
        
        for i, agent_id in enumerate(raw_agent_ids):
            agent_id_int = agent_id.item()
            if agent_id_int not in self.agent_id_to_index:
                if self.next_index >= 64:
                    skipped_count += 1
                    mapped_indices[i] = -1  # Mark as inactive
                    continue
                self.agent_id_to_index[agent_id_int] = self.next_index
                # Removed individual agent mapping prints for cleaner output
                self.next_index += 1
            
            mapped_indices[i] = self.agent_id_to_index[agent_id_int]
            
        if skipped_count > 0:
            print(f"⚠️ Skipped {skipped_count} agents due to 64-agent limit")
            # If we're skipping a lot, show some stats
            if skipped_count > 32:
                active_agents = (mapped_indices != -1).sum().item()
                print(f"📊 Active agents: {active_agents}, Mapped agents: {len(self.agent_id_to_index)}")
        
        # Pad to MAX_AGENTS for BatchedLinear compatibility
        MAX_AGENTS = 64
        if agent_count < MAX_AGENTS:
            # Removed padding message for cleaner output
            
            # Create padding for inactive agents
            padding_size = MAX_AGENTS - agent_count
            
            # Pad positions with zeros
            positions_padded = torch.zeros(MAX_AGENTS, 6, device=self.trainer.device)
            positions_padded[:agent_count] = positions
            
            # Pad agent indices (use -1 for inactive agents)
            agent_indices_padded = torch.full((MAX_AGENTS,), -1, dtype=torch.long, device=self.trainer.device)
            agent_indices_padded[:agent_count] = mapped_indices
            
            # Pad reward data with zeros
            reward_data_padded = torch.zeros(MAX_AGENTS, 4, device=self.trainer.device)
            reward_data_padded[:agent_count] = reward_data
            
            return positions_padded, agent_indices_padded, reward_data_padded
        
        return positions, mapped_indices, reward_data

    def _encode_actions(self, agent_indices: torch.Tensor, angles: torch.Tensor, 
                       walk: torch.Tensor, shoot: torch.Tensor) -> bytes:
        """Encode actions into ultra-compact binary format - only for active agents."""
        # Filter out inactive agents (agent_indices == -1)
        active_mask = agent_indices != -1
        active_count = active_mask.sum().item()
        
        if active_count == 0:
            return self._encode_empty_actions()
        
        # Get actions only for active agents
        angles_active = angles[active_mask]
        walk_active = walk[active_mask]
        shoot_active = shoot[active_mask]
        
        # Convert to numpy arrays (pure vectorized) - NO AGENT INDICES
        angles_np = angles_active.cpu().numpy().astype(np.float32)
        walk_np = walk_active.cpu().numpy().astype(np.float32)
        shoot_np = shoot_active.cpu().numpy().astype(np.float32)
        
        # Stack into action array [N, 3] - no loops, no indices!
        action_array = np.column_stack([angles_np, walk_np, shoot_np])
        
        # Convert to little-endian bytes
        action_bytes = action_array.astype('<f4').tobytes()
        
        # Create header: magic(4) + count(4) + tick(4) + action_size(4)
        header = struct.pack('<IIII', 0xACE5BEEF, active_count, self.tick_count, 3)
        
        return header + action_bytes

    def _encode_empty_actions(self) -> bytes:
        """Encode empty action response."""
        return struct.pack('<IIII', 0xACE5BEEF, 0, self.tick_count, 3)

    def end_round(self):
        """Signal end of round for learning updates."""
        # Reset cumulative rewards for next round
        self.trainer.on_round_end()
        # Clear agent ID mapping for next round
        self.agent_id_to_index.clear()
        self.next_index = 0
        # Removed round complete message for cleaner output

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

def initialize_transport(device='cpu'):
    """Initialize the transport layer and trainer."""
    global transport, trainer
    
    trainer = VectorizedTrainer(device=device)
    transport = BinaryTransport(trainer)
    
    print(f"✅ Binary transport ready on {device}")

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

def get_top_agent_parameters() -> Optional[Dict]:
    """Get the parameters of the best performing agent (deprecated - use WebSocket endpoint)."""
    # This function is deprecated in favor of the /top-agent WebSocket endpoint
    return None

def process_top_agent_data(binary_data: bytes) -> bytes:
    """Process single agent observation through the top performing agent."""
    print(f"🔍 Top agent received {len(binary_data)} bytes")
    
    if transport is None:
        print("⚠️ Transport not initialized")
        return _encode_empty_single_action()
    
    # Get the top agent index (highest cumulative reward)
    if transport.trainer.cumulative_rewards.numel() == 0:
        print("⚠️ No reward data available")
        return _encode_empty_single_action()
    
    # Get the all-time best agent index
    if hasattr(transport.trainer, 'best_agent_idx'):
        # Use the tracked all-time best agent
        top_agent_index = transport.trainer.best_agent_idx
        all_time_reward = transport.trainer.best_agents_ever[top_agent_index].item()
        current_reward = transport.trainer.cumulative_rewards[top_agent_index].item()
        print(f"🏆 Using ALL-TIME CHAMPION agent {top_agent_index} (all-time: {all_time_reward:.2f}, current: {current_reward:.2f})")
    else:
        # Fallback to current round best (old behavior)
        top_agent_index = torch.argmax(transport.trainer.cumulative_rewards).item()
        top_reward = transport.trainer.cumulative_rewards[top_agent_index].item()
        print(f"🏆 Using current round best agent {top_agent_index} (reward: {top_reward:.2f})")
    
    # Parse single agent observation
    obs_tensor = _parse_single_observation(binary_data)
    if obs_tensor is None:
        print("⚠️ Failed to parse observation")
        return _encode_empty_single_action()
    
    print(f"📊 Parsed observation: {obs_tensor}")
    
    # Run forward pass for just the top agent
    with torch.no_grad():
        # Expand observation to match batch size and fill with zeros except for top agent
        batched_obs = torch.zeros(MAX_AGENTS, obs_tensor.shape[-1], device=transport.trainer.device)
        batched_obs[top_agent_index] = obs_tensor
        
        # Forward pass through model
        x_actions, y_actions, walk_actions, shoot_actions, _ = transport.trainer.forward_pass(batched_obs)
        
        # Extract actions for the top agent only
        top_x = x_actions[top_agent_index].item()
        top_y = y_actions[top_agent_index].item()
        top_walk = walk_actions[top_agent_index].item()
        top_shoot = shoot_actions[top_agent_index].item()
    
    print(f"🎮 Generated actions - X: {top_x}, Y: {top_y}, Walk: {top_walk}, Shoot: {top_shoot}")
    
    # Encode single agent action
    return _encode_single_action(top_x, top_y, top_walk, top_shoot)

def _parse_single_observation(binary_data: bytes) -> Optional[torch.Tensor]:
    """Parse binary data for a single agent observation."""
    try:
        # Expected format: magic(4) + obs_size(4) + observation_data(obs_size * 4)
        if len(binary_data) < 8:
            return None
        
        magic, obs_size = struct.unpack('<II', binary_data[:8])
        if magic != 0xDEADBEEF:
            return None
        
        expected_size = 8 + obs_size * 4
        if len(binary_data) != expected_size:
            return None
        
        # Parse observation (just positions: my_pos(3), opp_pos(3))
        obs_data = struct.unpack(f'<{obs_size}f', binary_data[8:])
        obs_tensor = torch.tensor(obs_data, device=transport.trainer.device)
        
        return obs_tensor
        
    except Exception as e:
        print(f"Error parsing single observation: {e}")
        return None

def _encode_single_action(x_action: int, y_action: int, walk_action: int, shoot_action: int) -> bytes:
    """Encode actions for a single agent."""
    try:
        # Format: magic(4) + action_count(4) + actions(4*4)
        magic = 0xBEEFDEAD
        action_count = 1
        
        header = struct.pack('<II', magic, action_count)
        action_data = struct.pack('<IIII', x_action, y_action, walk_action, shoot_action)
        
        return header + action_data
        
    except Exception as e:
        print(f"Error encoding single action: {e}")
        return _encode_empty_single_action()

def _encode_empty_single_action() -> bytes:
    """Encode empty action for error cases."""
    magic = 0xBEEFDEAD
    action_count = 0
    return struct.pack('<II', magic, action_count)

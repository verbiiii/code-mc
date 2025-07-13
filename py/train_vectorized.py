#!/usr/bin/env python3
"""
Pure vectorized RL model - just the model and forward pass.
Zero Python loops, pure PyTorch operations.
"""

import torch
import numpy as np
from typing import Tuple


class VectorizedTrainer:
    """Pure vectorized RL trainer - model and learning only."""
    
    def __init__(self, max_agents=128, device='cpu'):
        self.max_agents = max_agents
        self.device = torch.device(device)
        
        # Model: [my_pos(3), opp_pos(3)] -> [x_logits(8), y_logits(8), walk_logit, shoot_logit]
        self.model = torch.nn.Sequential(
            torch.nn.Linear(6, 64),
            torch.nn.Tanh(),
            torch.nn.Linear(64, 128),
            torch.nn.Tanh(),
            torch.nn.Linear(128, 64),
            torch.nn.Tanh(),
            torch.nn.Linear(64, 18),  # 8 x-bins + 8 y-bins + walk + shoot
        ).to(self.device)
        
        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=1e-3)
        
        # Vectorized state tracking
        self.log_probs = torch.zeros((max_agents, 1000), device=self.device)
        self.rewards = torch.zeros((max_agents, 1000), device=self.device)
        self.episode_lengths = torch.zeros(max_agents, dtype=torch.long, device=self.device)
        self.reward_history = []
        
        print(f"🚀 VectorizedTrainer: {sum(p.numel() for p in self.model.parameters()):,} params on {device}")

    def forward_pass(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Pure forward pass on observation tensor.
        Args:
            observations: [N, 6] tensor of [my_x, my_y, my_z, opp_x, opp_y, opp_z]
        Returns:
            x_actions: [N] - sampled x direction bins (0-7)  
            y_actions: [N] - sampled y direction bins (0-7)
            walk_actions: [N] - boolean walk flags
            shoot_actions: [N] - boolean shoot flags
        """
        logits = self.model(observations)  # [N, 18]
        
        # Split outputs
        x_logits = logits[:, :8]      # [N, 8]
        y_logits = logits[:, 8:16]    # [N, 8] 
        walk_logits = logits[:, 16]   # [N]
        shoot_logits = logits[:, 17]  # [N]
        
        # Sample all actions simultaneously
        x_dist = torch.distributions.Categorical(logits=x_logits)
        y_dist = torch.distributions.Categorical(logits=y_logits)
        walk_dist = torch.distributions.Bernoulli(logits=walk_logits)
        shoot_dist = torch.distributions.Bernoulli(logits=shoot_logits)
        
        x_actions = x_dist.sample()      # [N]
        y_actions = y_dist.sample()      # [N]
        walk_actions = walk_dist.sample() # [N]
        shoot_actions = shoot_dist.sample() # [N]
        
        # Calculate log probabilities for training
        log_probs = (
            x_dist.log_prob(x_actions) +
            y_dist.log_prob(y_actions) +
            walk_dist.log_prob(walk_actions) +
            shoot_dist.log_prob(shoot_actions)
        )  # [N]
        
        return x_actions, y_actions, walk_actions.bool(), shoot_actions.bool(), log_probs

    def update_episode_data(self, agent_indices: torch.Tensor, rewards: torch.Tensor, log_probs: torch.Tensor):
        """Update episode buffers vectorized."""
        ep_lens = self.episode_lengths[agent_indices]
        valid_mask = ep_lens < 1000
        
        if valid_mask.any():
            valid_indices = agent_indices[valid_mask] 
            valid_rewards = rewards[valid_mask]
            valid_log_probs = log_probs[valid_mask]
            valid_lens = ep_lens[valid_mask]
            
            # Vectorized buffer update using advanced indexing
            self.rewards[valid_indices, valid_lens] = valid_rewards
            self.log_probs[valid_indices, valid_lens] = valid_log_probs
            self.episode_lengths[agent_indices] += 1

    def apply_reinforce_update(self, completed_indices: torch.Tensor):
        """Apply REINFORCE updates vectorized."""
        if len(completed_indices) == 0:
            return
            
        # Get episode data
        episode_lens = self.episode_lengths[completed_indices]
        max_len = episode_lens.max().item()
        
        if max_len == 0:
            return
            
        # Create timestep mask
        timestep_mask = torch.arange(max_len, device=self.device).unsqueeze(0) < episode_lens.unsqueeze(1)
        
        # Extract data
        log_probs = self.log_probs[completed_indices, :max_len]
        rewards = self.rewards[completed_indices, :max_len]
        
        # Calculate returns vectorized
        returns = torch.cumsum(torch.flip(rewards, [1]), dim=1)
        returns = torch.flip(returns, [1])
        
        # Normalize returns
        valid_returns = returns[timestep_mask]
        if len(valid_returns) > 1:
            return_std = valid_returns.std()
            if return_std > 0:
                normalized_returns = (returns - valid_returns.mean()) / return_std
            else:
                normalized_returns = returns - valid_returns.mean()
        else:
            normalized_returns = returns
        
        # Policy loss
        policy_loss = -(log_probs * normalized_returns * timestep_mask).sum() / timestep_mask.sum()
        
        # Update
        self.optimizer.zero_grad()
        policy_loss.backward()
        torch.nn.utils.clip_grad_norm_(self.model.parameters(), 1.0)
        self.optimizer.step()
        
        # Reset episodes
        self.episode_lengths[completed_indices] = 0
        self.log_probs[completed_indices] = 0
        self.rewards[completed_indices] = 0
        
        # Track performance
        avg_return = returns[:, 0].mean().item()
        self.reward_history.extend(returns[:, 0].cpu().numpy().tolist())
        if len(self.reward_history) > 1000:
            self.reward_history = self.reward_history[-1000:]
            
        print(f"🎯 {len(completed_indices)} episodes | Return: {avg_return:.2f} | Loss: {policy_loss.item():.4f}")

    def get_stats(self):
        """Get training statistics."""
        if not self.reward_history:
            return {"avg_return": 0.0, "episodes": 0}
        recent = self.reward_history[-100:]
        return {
            "avg_return": np.mean(recent),
            "std_return": np.std(recent), 
            "episodes": len(self.reward_history)
        }

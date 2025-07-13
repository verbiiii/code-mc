#!/usr/bin/env python3
"""
FMC Evolution trainer - compatible with the existing binary transport interface.
Provides the same API as VectorizedTrainer but uses evolutionary algorithms instead of gradient descent.
"""

import torch
import numpy as np
import jax
import jax.numpy as jnp
from typing import Tuple

from fmc import create_minecraft_fmc_trainer, MinecraftFMCTrainer


MAX_AGENTS = 64


class VectorizedTrainer:
    """
    FMC Evolution trainer with VectorizedTrainer-compatible interface.
    Drop-in replacement that uses FMC evolution instead of REINFORCE.
    """
    
    def __init__(self, device='cpu', use_fmc=True, num_agents=MAX_AGENTS):
        self.device = torch.device(device)
        self.max_agents = num_agents
        self.use_fmc = use_fmc
        
        if use_fmc:
            # Use FMC evolutionary trainer
            self.fmc_trainer = create_minecraft_fmc_trainer(num_agents=num_agents, seed=42)
            self.current_agent_mapping = {}  # Maps batch indices to agent IDs
            print(f"🧬 FMC Evolution Trainer: {num_agents} agents using evolutionary algorithm")
        else:
            # Fallback to original PyTorch REINFORCE (for comparison)
            self._init_pytorch_fallback()
            print(f"🚀 PyTorch REINFORCE Trainer: fallback mode")
        
        # Episode tracking for compatibility
        self.episode_lengths = torch.zeros(MAX_AGENTS, dtype=torch.long, device=self.device)
        self.reward_history = []
        
        # FMC-specific tracking
        self.completed_episodes = []
        self.agent_fitness_scores = []

    def _init_pytorch_fallback(self):
        """Initialize PyTorch REINFORCE model as fallback."""
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
        self.log_probs = torch.zeros((MAX_AGENTS, 1000), device=self.device)
        self.rewards = torch.zeros((MAX_AGENTS, 1000), device=self.device)

    def forward_pass(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        """
        Forward pass compatible with existing interface.
        Args:
            observations: [N, 6] tensor of [my_x, my_y, my_z, opp_x, opp_y, opp_z]
        Returns:
            x_actions: [N] - sampled x direction bins (0-7)  
            y_actions: [N] - sampled y direction bins (0-7)
            walk_actions: [N] - boolean walk flags
            shoot_actions: [N] - boolean shoot flags
            log_probs: [N] - log probabilities for REINFORCE (dummy for FMC)
        """
        if self.use_fmc:
            return self._forward_pass_fmc(observations)
        else:
            return self._forward_pass_pytorch(observations)
    
    def _forward_pass_fmc(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        """FMC-based forward pass using evolutionary algorithms."""
        batch_size = observations.shape[0]
        
        # Convert PyTorch tensor to numpy for JAX
        obs_np = observations.cpu().numpy()
        
        # Initialize action tensors
        x_actions = torch.zeros(batch_size, dtype=torch.long, device=self.device)
        y_actions = torch.zeros(batch_size, dtype=torch.long, device=self.device)
        walk_actions = torch.zeros(batch_size, dtype=torch.bool, device=self.device)
        shoot_actions = torch.zeros(batch_size, dtype=torch.bool, device=self.device)
        
        # Get actions from FMC agents (assign agents cyclically)
        for i in range(batch_size):
            agent_idx = i % self.fmc_trainer.num_agents
            self.current_agent_mapping[i] = agent_idx
            
            # Get actions from FMC agent
            actions = self.fmc_trainer.get_actions_for_agent(agent_idx, obs_np[i].tolist())
            
            x_actions[i] = actions["x_action"]
            y_actions[i] = actions["y_action"]
            walk_actions[i] = actions["walk_action"]
            shoot_actions[i] = actions["shoot_action"]
        
        # FMC doesn't use log probabilities, but we need to return something for compatibility
        dummy_log_probs = torch.zeros(batch_size, device=self.device)
        
        return x_actions, y_actions, walk_actions, shoot_actions, dummy_log_probs
    
    def _forward_pass_pytorch(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        """Original PyTorch REINFORCE forward pass."""
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
        """Update episode buffers - adapted for FMC."""
        if self.use_fmc:
            self._update_episode_data_fmc(agent_indices, rewards)
        else:
            self._update_episode_data_pytorch(agent_indices, rewards, log_probs)
    
    def _update_episode_data_fmc(self, agent_indices: torch.Tensor, rewards: torch.Tensor):
        """Update episode data for FMC evolution."""
        for i, reward in enumerate(rewards):
            batch_idx = agent_indices[i].item()
            
            # Map batch index to actual agent ID
            if batch_idx in self.current_agent_mapping:
                agent_idx = self.current_agent_mapping[batch_idx]
                
                # Update FMC trainer with reward
                self.fmc_trainer.update_episode_reward(agent_idx, reward.item())
                
                # Track episode length for compatibility
                self.episode_lengths[batch_idx] += 1
    
    def _update_episode_data_pytorch(self, agent_indices: torch.Tensor, rewards: torch.Tensor, log_probs: torch.Tensor):
        """Original PyTorch episode data update."""
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
        """Apply updates - REINFORCE for PyTorch, evolution for FMC."""
        if self.use_fmc:
            self._apply_fmc_update(completed_indices)
        else:
            self._apply_pytorch_update(completed_indices)
    
    def _apply_fmc_update(self, completed_indices: torch.Tensor):
        """Apply FMC evolutionary update."""
        completed_agents = []
        fitness_scores = []
        
        # Collect completed episodes and their fitness
        for batch_idx in completed_indices:
            batch_idx = batch_idx.item()
            
            if batch_idx in self.current_agent_mapping:
                agent_idx = self.current_agent_mapping[batch_idx]
                
                # Finish episode and get total reward as fitness
                total_reward = self.fmc_trainer.finish_episode(agent_idx)
                completed_agents.append(agent_idx)
                fitness_scores.append(total_reward)
                
                # Reset episode length for compatibility
                self.episode_lengths[batch_idx] = 0
                
                # Track performance
                self.reward_history.append(total_reward)
        
        # Try to trigger evolution
        if len(completed_agents) > 0:
            evolved = self.fmc_trainer.evolve_if_ready(completed_agents, fitness_scores)
            
            if evolved:
                stats = self.fmc_trainer.get_population_stats()
                avg_fitness = np.mean(fitness_scores) if fitness_scores else 0.0
                print(f"🧬 FMC Evolution: {len(completed_agents)} episodes | "
                      f"Avg Fitness: {avg_fitness:.2f} | "
                      f"Gen: {stats['generation']} | "
                      f"Pop Fitness: {stats['mean_fitness']:.2f}±{stats['std_fitness']:.2f}")
                
                # Clear agent mapping for next batch
                self.current_agent_mapping.clear()
    
    def _apply_pytorch_update(self, completed_indices: torch.Tensor):
        """Original PyTorch REINFORCE update."""
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
            
        print(f"🎯 PyTorch REINFORCE: {len(completed_indices)} episodes | "
              f"Return: {avg_return:.2f} | Loss: {policy_loss.item():.4f}")

    def get_stats(self):
        """Get training statistics."""
        if self.use_fmc:
            fmc_stats = self.fmc_trainer.get_population_stats()
            recent_rewards = self.reward_history[-100:] if self.reward_history else [0.0]
            
            return {
                "avg_return": np.mean(recent_rewards),
                "std_return": np.std(recent_rewards),
                "episodes": len(self.reward_history),
                "generation": fmc_stats["generation"],
                "population_fitness": fmc_stats["mean_fitness"],
                "population_diversity": fmc_stats["std_fitness"],
                "algorithm": "FMC Evolution"
            }
        else:
            if not self.reward_history:
                return {"avg_return": 0.0, "episodes": 0, "algorithm": "PyTorch REINFORCE"}
            recent = self.reward_history[-100:]
            return {
                "avg_return": np.mean(recent),
                "std_return": np.std(recent), 
                "episodes": len(self.reward_history),
                "algorithm": "PyTorch REINFORCE"
            }
    
    def save_model(self, filepath: str):
        """Save model/population state."""
        if self.use_fmc:
            self.fmc_trainer.save_population(filepath)
        else:
            torch.save({
                'model_state_dict': self.model.state_dict(),
                'optimizer_state_dict': self.optimizer.state_dict(),
                'reward_history': self.reward_history,
            }, filepath)
    
    def load_model(self, filepath: str):
        """Load model/population state."""
        if self.use_fmc:
            self.fmc_trainer.load_population(filepath)
        else:
            checkpoint = torch.load(filepath)
            self.model.load_state_dict(checkpoint['model_state_dict'])
            self.optimizer.load_state_dict(checkpoint['optimizer_state_dict'])
            self.reward_history = checkpoint.get('reward_history', [])


# Factory function for easy switching between algorithms
def create_trainer(algorithm="fmc", device='cpu', num_agents=MAX_AGENTS):
    """
    Create a trainer with the specified algorithm.
    
    Args:
        algorithm: "fmc" for FMC Evolution, "reinforce" for PyTorch REINFORCE
        device: PyTorch device
        num_agents: Number of agents in population
    
    Returns:
        VectorizedTrainer instance
    """
    use_fmc = (algorithm.lower() == "fmc")
    return VectorizedTrainer(device=device, use_fmc=use_fmc, num_agents=num_agents)

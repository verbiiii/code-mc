#!/usr/bin/env python3
"""
FMC (Fitness-Modified Competition) Evolution for Minecraft RL
Adapted from slimevolley JAX code to work with Java <> Python websocket communication.
Now includes the VectorizedTrainer interface for binary transport compatibility.
"""

import torch
import jax
import jax.numpy as jnp
import numpy as np
from typing import Dict, List, Tuple, Optional
import json

from parameter_groups.linear_group import LinearLayerParamGroup

# FMC hyperparameters adapted for Minecraft
NUM_AGENTS = 64  # Reduced for Minecraft environment
KEEP_TOP_PERCENT = 0.2

MUTATION_RATE = 0.1          # percent of weights that will be mutated (reduced for stability)
MUTATION_AMPLITUDE = 0.1     # amplitude of mutation noise (reduced for stability)

NUM_GENERATIONS = 1_000_000

FMC_BALANCE = 3.0


def relativize(vector: jnp.ndarray):
    """Convert scores to relative fitness using log/exp transforms."""
    std = vector.std()
    if std == 0:
        return jnp.ones(len(vector))
    standard = (vector - vector.mean()) / std
    standard = standard.at[standard > 0].set(jnp.log(1 + standard[standard > 0]) + 1)
    standard = standard.at[standard <= 0].set(jnp.exp(standard[standard <= 0]))
    return standard


class MinecraftAgents:
    """
    JAX-based agents adapted for Minecraft RL with websocket communication.
    Each agent has its own network parameters for isolated forward passes.
    """
    
    def __init__(
        self,
        num_agents: int,
        key: jax.random.PRNGKey,
        input_size: int = 6,  # [my_x, my_y, my_z, opp_x, opp_y, opp_z]
        hidden_size: int = 64,
        output_size: int = 18,  # 8 x-bins + 8 y-bins + walk + shoot
    ):
        self.key = key
        self.num_agents = num_agents
        
        # Create linear layer parameter groups for feed-forward network
        self.groups = (
            LinearLayerParamGroup(self.key, num_agents, input_size, hidden_size, 
                                mutation_rate=MUTATION_RATE, mutation_amplitude=MUTATION_AMPLITUDE),
            LinearLayerParamGroup(self.key, num_agents, hidden_size, hidden_size, 
                                mutation_rate=MUTATION_RATE, mutation_amplitude=MUTATION_AMPLITUDE),
            LinearLayerParamGroup(self.key, num_agents, hidden_size, output_size, 
                                mutation_rate=MUTATION_RATE, mutation_amplitude=MUTATION_AMPLITUDE),
        )

    @property
    def weights(self):
        """Returns a tuple of the weights for each group."""
        return tuple(group.weights for group in self.groups)
    
    @weights.setter
    def weights(self, weights):
        for group, w in zip(self.groups, weights):
            group.weights = w

    def forward(self, x):
        """
        Forward pass through all agents.
        Args:
            x: [num_agents, input_size] observations
        Returns:
            [num_agents, output_size] action logits
        """
        for group in self.groups:
            x = group.forward(x)
        return x

    def get_actions_for_agent(self, agent_idx: int, observations: jnp.ndarray):
        """
        Get actions for a specific agent.
        Args:
            agent_idx: Index of the agent
            observations: [6] tensor of [my_x, my_y, my_z, opp_x, opp_y, opp_z]
        Returns:
            Dict with action components
        """
        # Create batch with zeros for all agents, then set this agent's observations
        obs_batch = jnp.zeros((self.num_agents, 6))
        obs_batch = obs_batch.at[agent_idx].set(observations)
        
        # Get logits from forward pass for all agents
        all_logits = self.forward(obs_batch)  # [num_agents, 18]
        
        # Extract logits for this specific agent
        agent_logits = all_logits[agent_idx]  # [18]
        
        # Split outputs
        x_logits = agent_logits[:8]      # [8]
        y_logits = agent_logits[8:16]    # [8]
        walk_logit = agent_logits[16]    # scalar
        shoot_logit = agent_logits[17]   # scalar
        
        # Sample actions
        key = jax.random.PRNGKey(np.random.randint(0, 2**31))  # Use 2^31 instead of 2^32 for int32 compatibility
        key, *subkeys = jax.random.split(key, 5)
        
        x_action = jax.random.categorical(subkeys[0], x_logits)
        y_action = jax.random.categorical(subkeys[1], y_logits)
        walk_action = jax.random.bernoulli(subkeys[2], jax.nn.sigmoid(walk_logit))
        shoot_action = jax.random.bernoulli(subkeys[3], jax.nn.sigmoid(shoot_logit))
        
        return {
            "x_action": int(x_action),
            "y_action": int(y_action), 
            "walk_action": bool(walk_action),
            "shoot_action": bool(shoot_action)
        }

    def mutate(self, mutation_indices: jnp.ndarray):
        for group in self.groups:
            group.mutate(mutation_indices)

    def distances(self, parent_indices: jnp.ndarray):
        """Calculate parameter distances between agent pairs."""
        dists = jnp.zeros(len(parent_indices))
        for group in self.groups:
            dists += group.distances(parent_indices)
        return dists
    
    def clone(self, clone_indices: jnp.ndarray, partner_indices: jnp.ndarray):
        """Clone parameters from partners and mutate."""
        for group in self.groups:
            group.clone(clone_indices, partner_indices)


class MinecraftFMCTrainer:
    """
    FMC Evolution trainer adapted for Minecraft RL with websocket communication.
    Manages population evolution and integrates with Java message passing.
    """

    def __init__(
        self,
        num_agents: int = NUM_AGENTS,
        seed: int = 0,
    ):
        self.num_agents = num_agents
        self.key = jax.random.PRNGKey(seed)
        
        # Initialize agents with Minecraft-specific architecture
        self.key, subkey = jax.random.split(self.key)
        self.agents = MinecraftAgents(
            num_agents=num_agents,
            key=subkey,
            input_size=6,   # [my_x, my_y, my_z, opp_x, opp_y, opp_z]
            hidden_size=64,
            output_size=18  # 8 x-bins + 8 y-bins + walk + shoot
        )
        
        # Episode tracking
        self.episode_rewards = [[] for _ in range(num_agents)]
        self.episode_lengths = [0] * num_agents
        self.generation = 0
        self.fitness_scores = jnp.zeros(num_agents)
        
        print(f"🧬 MinecraftFMC: {num_agents} agents initialized for generation-based evolution")

    def get_actions_for_agent(self, agent_idx: int, observations: List[float]) -> Dict:
        """
        Get actions for a specific agent from websocket observations.
        Args:
            agent_idx: Index of the agent (0 to num_agents-1)
            observations: [my_x, my_y, my_z, opp_x, opp_y, opp_z]
        Returns:
            Dict with action components for JSON serialization
        """
        if not (0 <= agent_idx < self.num_agents):
            raise ValueError(f"Agent index {agent_idx} out of range [0, {self.num_agents})")
            
        obs_array = jnp.array(observations)
        return self.agents.get_actions_for_agent(agent_idx, obs_array)

    def update_episode_reward(self, agent_idx: int, reward: float):
        """Update reward for an agent's current episode."""
        if 0 <= agent_idx < self.num_agents:
            self.episode_rewards[agent_idx].append(reward)
            self.episode_lengths[agent_idx] += 1

    def finish_episode(self, agent_idx: int) -> float:
        """
        Finish an episode for an agent and return total reward.
        Args:
            agent_idx: Index of the agent
        Returns:
            total_reward: Sum of rewards for the episode
        """
        if 0 <= agent_idx < self.num_agents:
            total_reward = sum(self.episode_rewards[agent_idx])
            self.episode_rewards[agent_idx] = []
            self.episode_lengths[agent_idx] = 0
            return total_reward
        return 0.0

    def calculate_virtual_rewards(self, scores: jnp.ndarray, parent_indices: jnp.ndarray):
        """Calculate FMC virtual rewards = fitness^balance * diversity."""
        # Measure euclidean distance between parameter pairs
        distances = self.agents.distances(parent_indices)
        
        # Relativize both scores and distances
        rel_distances = relativize(distances)
        rel_scores = relativize(scores) ** FMC_BALANCE

        # Virtual rewards are the product of scores and distances
        virtual_rewards = rel_scores * rel_distances
        return virtual_rewards, distances

    def next_generation(self, scores: jnp.ndarray):
        """
        Perform one generation of FMC evolution.
        Args:
            scores: [num_agents] fitness scores
        """
        arange = jnp.arange(self.num_agents)

        self.key, sub0, sub1 = jax.random.split(self.key, 3)

        # Select random partners for each agent
        partner_indices = jax.random.choice(sub0, arange, shape=(self.num_agents,), replace=True)

        # Create parent pairs for distance calculation
        pair_indices = jnp.stack((arange, partner_indices), axis=1)

        # Calculate virtual rewards
        virtual_rewards, distances = self.calculate_virtual_rewards(scores, pair_indices)
        partner_virtual_rewards = virtual_rewards[partner_indices]

        # Determine cloning probability based on virtual reward difference
        value_diff = (partner_virtual_rewards - virtual_rewards) / jnp.where(virtual_rewards > 0, virtual_rewards, 1e-8)

        # Random selection for cloning
        random_vals = jax.random.uniform(sub1, (self.num_agents,))
        will_clone = value_diff >= random_vals

        # Protect top performers from being cloned over
        top_agent_indices = scores.argsort()[-int(self.num_agents * KEEP_TOP_PERCENT):]
        will_clone = jnp.where(jnp.isin(arange, top_agent_indices), False, will_clone)
        
        # Extract indices of agents that will clone
        clone_indices = arange[will_clone]
        clone_partner_indices = partner_indices[will_clone]

        # Perform cloning and mutation
        if len(clone_indices) > 0:
            self.agents.clone(clone_indices, clone_partner_indices)

        self.generation += 1
        
        # Log evolution stats
        cloned_count = len(clone_indices)
        print(f"🧬 Gen {self.generation}: "
              f"fitness={scores.mean():.3f}±{scores.std():.3f} "
              f"(max={scores.max():.3f}), "
              f"diversity={distances.mean():.3f}±{distances.std():.3f}, "
              f"cloned={cloned_count}/{self.num_agents}")

    def evolve_if_ready(self, completed_agents: List[int], fitness_scores: List[float]) -> bool:
        """
        Trigger evolution if enough episodes are completed.
        Args:
            completed_agents: List of agent indices that completed episodes
            fitness_scores: List of fitness scores for completed agents
        Returns:
            bool: True if evolution was triggered
        """
        if len(completed_agents) >= self.num_agents * 0.8:  # 80% completion threshold
            # Create fitness array for all agents
            full_fitness = jnp.zeros(self.num_agents)
            
            for agent_idx, fitness in zip(completed_agents, fitness_scores):
                if agent_idx < self.num_agents:
                    full_fitness = full_fitness.at[agent_idx].set(fitness)
            
            self.fitness_scores = full_fitness
            
            # Evolve population
            self.next_generation(full_fitness)
            
            return True
        return False

    def get_best_agent_idx(self) -> int:
        """Get index of best performing agent."""
        return int(jnp.argmax(self.fitness_scores))

    def get_population_stats(self) -> Dict:
        """Get statistics about the current population."""
        return {
            "generation": self.generation,
            "population_size": self.num_agents,
            "mean_fitness": float(self.fitness_scores.mean()),
            "std_fitness": float(self.fitness_scores.std()),
            "max_fitness": float(self.fitness_scores.max()),
            "min_fitness": float(self.fitness_scores.min()),
        }

    def save_population(self, filepath: str):
        """Save the population state."""
        save_data = {
            "generation": self.generation,
            "fitness_scores": np.array(self.fitness_scores),
            "weights": [np.array(w) for w in self.agents.weights],
        }
        np.savez(filepath, **save_data)
        print(f"💾 Population saved to {filepath}")

    def load_population(self, filepath: str):
        """Load population state."""
        save_data = np.load(filepath)
        self.generation = int(save_data["generation"])
        self.fitness_scores = jnp.array(save_data["fitness_scores"])
        
        # Reconstruct weights
        weights = tuple(jnp.array(save_data[f"arr_{i+2}"]) for i in range(len(self.agents.groups)))
        self.agents.weights = weights
        
        print(f"📂 Population loaded from {filepath}, generation {self.generation}")


# Main interface function for websocket integration
def create_minecraft_fmc_trainer(num_agents: int = NUM_AGENTS, seed: int = 0) -> MinecraftFMCTrainer:
    """
    Factory function to create a Minecraft FMC trainer.
    
    Args:
        num_agents: Number of agents in the population
        seed: Random seed for reproducibility
        
    Returns:
        MinecraftFMCTrainer instance
    """
    return MinecraftFMCTrainer(num_agents=num_agents, seed=seed)


# === VectorizedTrainer Interface for Binary Transport Compatibility ===

MAX_AGENTS = 64


class VectorizedTrainer:
    """
    FMC Evolution trainer with VectorizedTrainer-compatible interface.
    Direct integration of FMC evolution with binary transport protocol.
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

if __name__ == "__main__":
    # Example usage / testing
    print("🧬 Testing MinecraftFMCTrainer...")
    
    # Create trainer with small population for testing
    trainer = create_minecraft_fmc_trainer(num_agents=8, seed=42)
    
    # Test action generation
    test_observations = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0]  # [my_x, my_y, my_z, opp_x, opp_y, opp_z]
    
    print("\n🎮 Testing action generation:")
    for agent_idx in range(3):  # Test first 3 agents
        actions = trainer.get_actions_for_agent(agent_idx, test_observations)
        print(f"Agent {agent_idx}: {actions}")
    
    # Test episode tracking and evolution
    print("\n🔄 Testing episode tracking and evolution:")
    
    # Simulate some episodes
    for episode in range(3):
        completed_agents = []
        fitness_scores = []
        
        for agent_idx in range(trainer.num_agents):
            # Simulate episode rewards
            for step in range(10):
                reward = np.random.uniform(-1, 1)
                trainer.update_episode_reward(agent_idx, reward)
            
            # Finish episode
            total_reward = trainer.finish_episode(agent_idx)
            completed_agents.append(agent_idx)
            fitness_scores.append(total_reward)
        
        # Try to trigger evolution
        evolved = trainer.evolve_if_ready(completed_agents, fitness_scores)
        if evolved:
            stats = trainer.get_population_stats()
            print(f"Episode {episode}: Evolution triggered! Stats: {stats}")
    
    print("\n✅ FMC Trainer test completed successfully!")
    print("🚀 Ready for integration with Java websocket communication!")

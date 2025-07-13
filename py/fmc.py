#!/usr/bin/env python3
"""
FMC (Fitness-Modified Competition) Evolution for Minecraft RL
Adapted from slimevolley JAX code to work with Java <> Python websocket communication.
"""

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
        # Reshape observations for this agent
        obs_batch = jnp.expand_dims(observations, 0)  # [1, 6]
        
        # Get logits from forward pass
        logits = self.forward(obs_batch)  # [1, 18]
        agent_logits = logits[0]  # [18]
        
        # Split outputs
        x_logits = agent_logits[:8]      # [8]
        y_logits = agent_logits[8:16]    # [8]
        walk_logit = agent_logits[16]    # scalar
        shoot_logit = agent_logits[17]   # scalar
        
        # Sample actions
        key = jax.random.PRNGKey(np.random.randint(0, 2**32))
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

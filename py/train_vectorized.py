import torch
import numpy as np
from typing import Tuple
from batched_layers import BatchedLinear

MAX_AGENTS = 64

class VectorizedTrainer:
    def __init__(self, device='cpu'):
        self.device = torch.device(device)

        # Model with BatchedLinear layers
        self.model = torch.nn.Sequential(
            BatchedLinear(MAX_AGENTS, 6, 64),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 64, 128),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 128, 64),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 64, 18),
        ).to(self.device)

        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=1e-3)

        self.log_probs = torch.zeros((MAX_AGENTS, 1000), device=self.device)
        self.rewards = torch.zeros((MAX_AGENTS, 1000), device=self.device)
        self.episode_lengths = torch.zeros(MAX_AGENTS, dtype=torch.long, device=self.device)
        self.reward_history = []

        # Fitness tracking
        self.cumulative_rewards = torch.zeros(MAX_AGENTS, device=self.device)

        print(f"🚀 RLAgents: {sum(p.numel() for p in self.model.parameters()):,} params on {device}")

    def forward_pass(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        logits = self.model(observations)
        x_logits = logits[:, :8]
        y_logits = logits[:, 8:16]
        walk_logits = logits[:, 16]
        shoot_logits = logits[:, 17]

        x_dist = torch.distributions.Categorical(logits=x_logits)
        y_dist = torch.distributions.Categorical(logits=y_logits)
        walk_dist = torch.distributions.Bernoulli(logits=walk_logits)
        shoot_dist = torch.distributions.Bernoulli(logits=shoot_logits)

        x_actions = x_dist.sample()
        y_actions = y_dist.sample()
        walk_actions = walk_dist.sample()
        shoot_actions = shoot_dist.sample()

        log_probs = (
            x_dist.log_prob(x_actions) +
            y_dist.log_prob(y_actions) +
            walk_dist.log_prob(walk_actions) +
            shoot_dist.log_prob(shoot_actions)
        )

        return x_actions, y_actions, walk_actions.bool(), shoot_actions.bool(), log_probs

    def update_episode_data(self, agent_indices: torch.Tensor, reward_data: torch.Tensor, log_probs: torch.Tensor):
        """Update episode data using actual agent indices."""
        # Filter out inactive agents (agent_indices == -1)
        active_mask = agent_indices != -1
        if not active_mask.any():
            return  # No active agents
            
        active_indices = agent_indices[active_mask]
        active_reward_data = reward_data[active_mask]
        
        dmg_dealt = active_reward_data[:, 0]
        dmg_taken = active_reward_data[:, 1]
        kills = active_reward_data[:, 2]
        deaths = active_reward_data[:, 3]

        rewards = dmg_dealt - dmg_taken + (100 * kills) - (100 * deaths)
        
        # Use the actual agent indices from the data
        self.cumulative_rewards[active_indices] += rewards
        
        # Debug: Only print non-zero rewards
        non_zero_mask = rewards != 0
        if non_zero_mask.any():
            for i, (idx, reward) in enumerate(zip(active_indices[non_zero_mask], rewards[non_zero_mask])):
                print(f"Agent {idx}: +{reward:.2f} (cumulative: {self.cumulative_rewards[idx]:.2f})")

    def reset_cumulative_rewards(self):
        """Reset cumulative rewards at the end of each round."""
        self.cumulative_rewards.zero_()
        print("🔄 Cumulative rewards reset for new round")

    def apply_reinforce_update(self):
        """Apply REINFORCE learning updates at end of round."""
        # TODO: Implement actual REINFORCE update logic
        print("🎯 Applied REINFORCE updates")

    # def apply_fmc(self, completed_indices: torch.Tensor):
    #     print("🧬 Fitness values (cumulative rewards):")
    #     for idx in completed_indices.tolist():
    #         print(f" - Agent {idx}: {self.cumulative_rewards[idx].item():.2f}")

    def get_stats(self):
        if not self.reward_history:
            return {"avg_return": 0.0, "episodes": 0}
        recent = self.reward_history[-100:]
        return {
            "avg_return": np.mean(recent),
            "std_return": np.std(recent),
            "episodes": len(self.reward_history)
        }

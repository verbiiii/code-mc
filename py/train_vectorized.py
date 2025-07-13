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

        print(f"🚀 VectorizedTrainer: {sum(p.numel() for p in self.model.parameters()):,} params on {device}")

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

    def update_episode_data(self, agent_indices: torch.Tensor, rewards: torch.Tensor, log_probs: torch.Tensor):
        ep_lens = self.episode_lengths[agent_indices]
        valid_mask = ep_lens < 1000

        if valid_mask.any():
            valid_indices = agent_indices[valid_mask]
            valid_rewards = rewards[valid_mask]
            valid_log_probs = log_probs[valid_mask]
            valid_lens = ep_lens[valid_mask]

            self.rewards[valid_indices, valid_lens] = valid_rewards
            self.log_probs[valid_indices, valid_lens] = valid_log_probs
            self.episode_lengths[agent_indices] += 1

    def apply_reinforce_update(self, completed_indices: torch.Tensor):
        if len(completed_indices) == 0:
            return

        episode_lens = self.episode_lengths[completed_indices]
        max_len = episode_lens.max().item()

        if max_len == 0:
            return

        timestep_mask = torch.arange(max_len, device=self.device).unsqueeze(0) < episode_lens.unsqueeze(1)
        log_probs = self.log_probs[completed_indices, :max_len]
        rewards = self.rewards[completed_indices, :max_len]

        returns = torch.cumsum(torch.flip(rewards, [1]), dim=1)
        returns = torch.flip(returns, [1])

        valid_returns = returns[timestep_mask]
        if len(valid_returns) > 1:
            return_std = valid_returns.std()
            if return_std > 0:
                normalized_returns = (returns - valid_returns.mean()) / return_std
            else:
                normalized_returns = returns - valid_returns.mean()
        else:
            normalized_returns = returns

        policy_loss = -(log_probs * normalized_returns * timestep_mask).sum() / timestep_mask.sum()

        self.optimizer.zero_grad()
        policy_loss.backward()
        torch.nn.utils.clip_grad_norm_(self.model.parameters(), 1.0)
        self.optimizer.step()

        self.episode_lengths[completed_indices] = 0
        self.log_probs[completed_indices] = 0
        self.rewards[completed_indices] = 0

        avg_return = returns[:, 0].mean().item()
        self.reward_history.extend(returns[:, 0].cpu().numpy().tolist())
        if len(self.reward_history) > 1000:
            self.reward_history = self.reward_history[-1000:]

        print(f"🎯 {len(completed_indices)} episodes | Return: {avg_return:.2f} | Loss: {policy_loss.item():.4f}")

    def get_stats(self):
        if not self.reward_history:
            return {"avg_return": 0.0, "episodes": 0}
        recent = self.reward_history[-100:]
        return {
            "avg_return": np.mean(recent),
            "std_return": np.std(recent),
            "episodes": len(self.reward_history)
        }


import torch
import numpy as np
from observations import VectorizedObservations
from rl_operator import RLOperators

# FMC Constants
KEEP_TOP_PERCENT = 0.2
FMC_BALANCE = 3.0

class VectorizedTrainer:
    def __init__(self, device='cpu', num_agents: int = 32):
        self.num_agents = num_agents
        self.device = torch.device(device)
        self.operators = RLOperators(device=self.device, num_agents=self.num_agents).to(self.device)
        self.reward_history = []
        self.num_updates = 0
        self.tick_count = 0

        # Fitness tracking
        self.round_cumulative_rewards = torch.zeros(num_agents, device=self.device)  # Current round rewards
        self.lifetime_cumulative_rewards = torch.zeros(num_agents, device=self.device)  # Never reset unless cloned

        print(f"🚀 RLAgents: {sum(p.numel() for p in self.operators.parameters()):,} params on {device}")

    def tick(self, observations: VectorizedObservations):
        self.tick_count += 1
        self.calculate_rewards(observations)
        if self.tick_count % 10 == 0:
            self.apply_fmc_update(observations)
        return self.forward(observations)

    def forward(self, observations: VectorizedObservations):
        """
        observations: [N, 3] – only alive agents
        agent_indices: [N] – indices of those agents (0 <= index < MAX_AGENTS)
        group_indices: [N] – group id per agent (used to match group-mates)
        """
        
        y = self.operators.forward(observations)

        # Split output logits
        x_logits = y[:, :8]
        walk_logits = y[:, 8]
        shoot_logits = y[:, 9]
        jump_logits = y[:, 10]
        sneak_logits = y[:, 11]
        pitch_logits = y[:, 12:20]
        yaw_logits = y[:, 20:28]

        # Deterministic policy
        movement_theta = torch.argmax(x_logits, dim=1)
        walk_actions = (walk_logits > 0.0)
        shoot_actions = (shoot_logits > 0.0)
        jump_actions = (jump_logits > 0.0)
        sneak_actions = (sneak_logits > 0.0)
        pitch_actions = torch.argmax(pitch_logits, dim=1)
        yaw_actions = torch.argmax(yaw_logits, dim=1)

        return movement_theta, walk_actions, shoot_actions, jump_actions, sneak_actions, pitch_actions, yaw_actions

    def calculate_rewards(self, obs: VectorizedObservations):
        """Update episode data using actual agent indices."""
        # Filter out inactive agents (agent_indices == -1)
        active_mask = obs.agent_indices != -1
        if not active_mask.any():
            return  # No active agents
            
        active_indices = obs.agent_indices[active_mask]

        dmg_dealt = obs.damage_dealt[active_mask]
        dmg_taken = obs.damage_taken[active_mask]
        kills = obs.kills[active_mask]
        deaths = obs.deaths[active_mask]
        num_bullets = obs.num_bullets[active_mask]
        positions = obs.positions[active_mask]

        # print(dmg_dealt.mean(), "dmg avg")
        # print(kills.mean(), "kills avg")
        # print(deaths.mean(), "deaths avg")
        # print(num_bullets.mean(), "bullets avg")

        # calculate each agent's distance to `x=-38, y=0, z=2`
        target_position = torch.tensor([-38.0, 0.0, 2.0], device=self.device)  # NOTE: keep this in mind
        distances = torch.norm(positions[active_mask] - target_position, dim=1)
        multiplier = 1 / (distances + 1)

        # dampened rewards
        BASELINE_REWARD = 0
        self.current_rewards = torch.ones(active_mask.sum(), device=self.device, dtype=torch.float32) * BASELINE_REWARD
        self.current_rewards += ((dmg_dealt * 1.0) + (kills * 10)) * multiplier
        self.current_rewards -= (dmg_taken * 0.1) + (deaths * 1.0)
        self.current_rewards -= num_bullets * 0.01

        # print(self.current_rewards.mean().item(), "current rewards avg")
        # print(self.current_rewards.max().item(), "current rewards max")
        
        # Use the actual agent indices from the data
        self.round_cumulative_rewards[active_indices] += self.current_rewards
        self.lifetime_cumulative_rewards[active_indices] += self.current_rewards  # Also update lifetime rewards
        self.num_updates += 1

        # update rewards
        obs.rewards[active_mask] = self.current_rewards

    def on_round_end(self):
        """Called at the end of each round."""

        # self.apply_fmc_update()  # Apply FMC first while we still have cumulative rewards
        self.reset_cumulative_rewards()  # Then reset ONLY current round rewards

    def apply_fmc_update(self, obs: VectorizedObservations):
        """Apply FMC (Functional Mutation and Crossover) updates to the model parameters."""

        # print("This Round's Cumulative Rewards:")
        # print(self.round_cumulative_rewards)
            
        # scores = self.current_rewards.clone()
        metric_for_top_k = self.round_cumulative_rewards.clone()
        scores = self.round_cumulative_rewards.clone()
        # scores = self.lifetime_cumulative_rewards.clone()
        
        # Select partners uniformly at random
        partner_indices = torch.randint(0, self.num_agents, (self.num_agents,), device=self.device)
        
        # distance_partner_is = torch.multinomial(normalized_scores, MAX_AGENTS, replacement=True)
        
        # Calculate virtual rewards
        vr = self._calculate_virtual_rewards(scores, partner_indices)
        partner_vr = vr[partner_indices]
        
        # Determine cloning probability based on virtual rewards
        value = (partner_vr - vr) / torch.where(vr > 0, vr, torch.tensor(1e-8, device=self.device))
        
        # Random threshold for cloning decision
        r = torch.rand(self.num_agents, device=self.device)
        will_clone = value >= r

        # force clone if dead
        will_clone[obs.deaths > 0] = True  # Force clone if agent died this round

        # Protect top agents from being cloned (they keep their parameters)
        top_k = max(int(self.num_agents * KEEP_TOP_PERCENT), 1)
        if top_k <= 0:
            raise ValueError("KEEP_TOP_PERCENT must be greater than 0 to protect at least one agent.")

        top_agent_indices = torch.topk(metric_for_top_k, top_k).indices
        will_clone[top_agent_indices] = False

        # will_perturbate = torch.ones(self.num_agents, device=self.device, dtype=torch.bool)
        # will_perturbate[top_agent_indices] = False  # Don't perturb top agents
        will_perturbate = will_clone.clone()  # Perturb all cloned agents

        # Get top k rewards for display
        top_k_rewards = scores[top_agent_indices] if top_k > 0 else torch.tensor([])

        top_k_lifetime_rewards = self.lifetime_cumulative_rewards[top_agent_indices] if top_k > 0 else torch.tensor([])

        self.operators.blend_parameters(partner_indices, will_clone, will_perturbate)
            
        # CRITICAL: Reset lifetime rewards for cloned agents (they have new brains now)
        self.round_cumulative_rewards[will_clone] = 0.0
        self.lifetime_cumulative_rewards[will_clone] = 0.0
        
        # Enhanced FMC metrics
        num_cloned = will_clone.sum().item()
        mean_score = scores.mean().item()
        std_score = scores.std().item()
        max_score = scores.max().item()
        
        if num_cloned > 0:
            print(f"🧬 Agents Updated:")
            print(f"   📊 Scores: μ={mean_score:.2f}, σ={std_score:.2f}, max={max_score:.2f}")
            print(f"   🔄 Cloned: {num_cloned}/{self.num_agents} agents (protected top {top_k})")
            if len(top_k_rewards) > 0:
                print(f"   🏆 Top {top_k} rewards: {top_k_rewards.tolist()}")
                print(f"   🏅 Top {top_k} lifetimes (alive): {top_k_lifetime_rewards.tolist()}")
                print(f"       🏆 Best Agent Index: {top_agent_indices[0].item()}")

    def _calculate_virtual_rewards(self, scores: torch.Tensor, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate virtual rewards based on scores and parameter distances."""
        # Calculate distances between agents and their partners
        dists = self.operators.calculate_distances(partner_indices)
        
        # Relativize distances and scores
        rel_dists = self._relativize(dists)
        rel_scores = self._relativize(scores) ** FMC_BALANCE
        
        # Virtual rewards are the product of relativized scores and distances
        return rel_scores * rel_dists

    def _relativize(self, vector: torch.Tensor) -> torch.Tensor:
        """Relativize a vector using log/exp transformation as in the JAX implementation."""
        std = vector.std()
        if std == 0:
            return torch.ones_like(vector)
        
        standard = (vector - vector.mean()) / std
        
        # Apply log transformation for positive values
        positive_mask = standard > 0
        standard[positive_mask] = torch.log(1 + standard[positive_mask]) + 1
        
        # Apply exp transformation for non-positive values
        non_positive_mask = standard <= 0
        standard[non_positive_mask] = torch.exp(standard[non_positive_mask])
        
        return standard

    def reset_cumulative_rewards(self):
        """Reset cumulative rewards for all agents."""
        self.round_cumulative_rewards.zero_()

    def top_k_agent_indices(self, k: int = 5) -> torch.Tensor:
        """Get indices of top-k agents based on cumulative rewards."""
        if self.round_cumulative_rewards.numel() == 0:
            return torch.tensor([], device=self.device, dtype=torch.long)
        
        top_k_values, top_k_indices = torch.topk(self.round_cumulative_rewards, k, sorted=True)
        return top_k_indices

    def get_stats(self):
        if not self.reward_history:
            return {"avg_return": 0.0, "episodes": 0}
        recent = self.reward_history[-100:]
        return {
            "avg_return": np.mean(recent),
            "std_return": np.std(recent),
            "episodes": len(self.reward_history)
        }

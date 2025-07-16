import torch
import numpy as np
from typing import Tuple
from batched_layers import BatchedLinear

MAX_AGENTS = 64

# FMC Constants
KEEP_TOP_PERCENT = 0.2
MUTATION_AMPLITUDE = 0.001    # maximum amplitude of the mutation (std dev for normal distribution)
FMC_BALANCE = 2.0


class RLOperator(torch.nn.Module):
    def __init__(self, device='cpu'):
        super(RLOperator, self).__init__()

        self.device = torch.device(device)

        self.input_features = 6

        # Model with BatchedLinear layers - updated for pitch/yaw aiming + jump/sneak
        self.model = torch.nn.Sequential(
            BatchedLinear(MAX_AGENTS, self.input_features, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 32),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 32, 28),  # [theta(8) + walk(1) + shoot(1) + jump(1) + sneak(1) + pitch(8) + yaw(8)]
        ).to(self.device)

    def forward(self, x: torch.Tensor, agent_indices: torch.Tensor):
        # let's zero-pad all of the agent indices that are missing
        padded_x = torch.zeros((MAX_AGENTS, self.input_features), device=self.device)        
        padded_x[agent_indices] = x

        return self.model(padded_x)

class VectorizedTrainer:
    def __init__(self, device='cpu'):

        self.device = torch.device(device)
        self.model = RLOperator(device=self.device).to(self.device)

        self.reward_history = []
        
        self.num_updates = 0

        # Fitness tracking
        self.round_cumulative_rewards = torch.zeros(MAX_AGENTS, device=self.device)  # Current round rewards
        self.lifetime_cumulative_rewards = torch.zeros(MAX_AGENTS, device=self.device)  # Never reset unless cloned

        print(f"🚀 RLAgents: {sum(p.numel() for p in self.model.parameters()):,} params on {device}")

    def forward_pass(self, observations: torch.Tensor, agent_indices: torch.Tensor, group_indices: torch.Tensor, team_indices: torch.Tensor):
        logits = self.model.forward(observations, agent_indices)

        raise NotImplementedError("TODO handle group indices and team indices...")

        # x1, y1, z1 = self (agent's position)
        x1_coords = observations[:, 0]
        y1_coords = observations[:, 1]
        z1_coords = observations[:, 2]

        # TODO: we need to convert from always having just 1 enemy at at ime, to having multiple enemies (of unknown size)
        # x2, y2, z2 = target (enemy's position)
        # x2_coords = observations[:, 3]
        # y2_coords = observations[:, 4]
        # z2_coords = observations[:, 5]

        distance_to_enemy = torch.sqrt((x2_coords - x1_coords) ** 2 + (y2_coords - y1_coords) ** 2 + (z2_coords - z1_coords) ** 2)

        x_logits = logits[:, :8]
        walk_logits = logits[:, 8]
        shoot_logits = logits[:, 9]
        jump_logits = logits[:, 10]
        sneak_logits = logits[:, 11]
        pitch_logits = logits[:, 12:20]  # 8 categories for pitch (-90 to +90 degrees)
        yaw_logits = logits[:, 20:28]    # 8 categories for yaw (0 to 360 degrees)

        # Deterministic actions - take argmax instead of sampling
        movement_theta = torch.argmax(x_logits, dim=1)
        walk_actions = (walk_logits > 0.0).bool()  # Deterministic threshold at 0
        shoot_actions = (shoot_logits > 0.0).bool()
        jump_actions = (jump_logits > 0.0).bool()
        sneak_actions = (sneak_logits > 0.0).bool()
        pitch_actions = torch.argmax(pitch_logits, dim=1)
        yaw_actions = torch.argmax(yaw_logits, dim=1)

        # No log probabilities for deterministic policies
        log_probs = None

        return movement_theta, walk_actions, shoot_actions, jump_actions, sneak_actions, pitch_actions, yaw_actions, log_probs, distance_to_enemy

    def update_episode_data(self, agent_indices: torch.Tensor, reward_data: torch.Tensor, log_probs, distance_to_enemy: torch.Tensor):
        """Update episode data using actual agent indices."""
        # Filter out inactive agents (agent_indices == -1)
        active_mask = agent_indices != -1
        if not active_mask.any():
            return  # No active agents
        
        # set our random seed to be `self.num_updates` (TODO expand on this)
        # torch.manual_seed(self.num_updates)
            
        active_indices = agent_indices[active_mask]
        active_reward_data = reward_data[active_mask]
        
        dmg_dealt = active_reward_data[:, 0]
        dmg_taken = active_reward_data[:, 1]
        kills = active_reward_data[:, 2]
        deaths = active_reward_data[:, 3]

        # rewards = dmg_dealt - (dmg_taken * 0.1) + (100 * kills) - (10 * deaths)  # asymmetrical reward (cheap pain)
        rewards = dmg_dealt - dmg_taken + (100 * kills) - (100 * deaths)  # symmetrical reward
        # rewards = dmg_dealt - (dmg_taken * 10) + (100 * kills) - (1000 * deaths)  # asymmetrical reward (expensive pain)

        # give a small reward for being close to the enemy (baseline 100 blocks)
        rewards += (1 / (distance_to_enemy[active_mask] + 1))
        
        # Use the actual agent indices from the data
        self.round_cumulative_rewards[active_indices] += rewards
        self.lifetime_cumulative_rewards[active_indices] += rewards  # Also update lifetime rewards
        
        # Debug: Only print non-zero rewards
        non_zero_mask = rewards != 0
        if non_zero_mask.any():
            pass  # Removed individual agent reward prints for cleaner output
        
        # Note: log_probs is None for deterministic policies, so we don't store them

        self.num_updates += 1

    def on_round_end(self):
        """Called at the end of each round."""

        self.apply_fmc_update()  # Apply FMC first while we still have cumulative rewards
        self.reset_cumulative_rewards()  # Then reset ONLY current round rewards

    def apply_fmc_update(self):
        """Apply FMC (Functional Mutation and Crossover) updates to the model parameters."""

        print("This Round's Cumulative Rewards:")
        print(self.round_cumulative_rewards)
            
        scores = self.round_cumulative_rewards.clone()
        # scores = self.lifetime_cumulative_rewards.clone()
        arange = torch.arange(MAX_AGENTS, device=self.device)
        
        # Select partners uniformly at random
        # partner_indices = torch.randint(0, MAX_AGENTS, (MAX_AGENTS,), device=self.device)

        # Select partners based on scores (higher score higher probability of selection)
        normalized_scores = torch.clamp((scores - scores.min()) / (scores.max() - scores.min()), min=0.01)

        # check for nans or infs (crash if so)
        if torch.isnan(normalized_scores).any() or torch.isinf(normalized_scores).any():
            raise ValueError("Normalized scores contain NaN or Inf values. Check your reward calculations.")
        
        distance_partner_is = torch.multinomial(normalized_scores, MAX_AGENTS, replacement=True)
        
        # Calculate virtual rewards
        vr = self._calculate_virtual_rewards(scores, arange, distance_partner_is)
        clone_partner_indices = torch.multinomial(vr, MAX_AGENTS, replacement=True)
        
        # Determine cloning probability based on virtual rewards
        # value = (partner_vr - vr) / torch.where(vr > 0, vr, torch.tensor(1e-8, device=self.device))
        
        # Random threshold for cloning decision
        # r = torch.rand(MAX_AGENTS, device=self.device)
        # will_clone = value >= r
        clone_percent = 0.75
        # generate a will clone mask totally randomly using our clone percent
        will_clone = torch.rand(MAX_AGENTS, device=self.device) < clone_percent
        
        # Protect top agents from being cloned (they keep their parameters)
        top_k = max(int(MAX_AGENTS * KEEP_TOP_PERCENT), 1)
        if top_k <= 0:
            raise ValueError("KEEP_TOP_PERCENT must be greater than 0 to protect at least one agent.")

        top_agent_indices = torch.topk(scores, top_k).indices
        will_clone[top_agent_indices] = False
        
        # Get top k rewards for display
        top_k_rewards = scores[top_agent_indices] if top_k > 0 else torch.tensor([])
        
        # Perform cloning and mutation
        if will_clone.any():
            # clone_indices_to_clone_from = partner_indices[will_clone]
            
            # Clone parameters for each BatchedLinear layer in the model
            for module in self.model.modules():
                if isinstance(module, BatchedLinear):
                    module.clone(will_clone, clone_partner_indices)
                    # Mutate the cloned parameters
                    module.mutate(will_clone, MUTATION_AMPLITUDE)
            
            # CRITICAL: Reset lifetime rewards for cloned agents (they have new brains now)
            self.lifetime_cumulative_rewards[will_clone] = 0.0
            cloned_count = will_clone.sum().item()
            print(f"🧠 Reset lifetime rewards for {cloned_count} cloned agents (new brains)")
            
            # Enhanced FMC metrics
            num_cloned = will_clone.sum().item()
            mean_score = scores.mean().item()
            std_score = scores.std().item()
            max_score = scores.max().item()
            
            print(f"🧬 FMC Evolution:")
            print(f"   📊 Scores: μ={mean_score:.2f}, σ={std_score:.2f}, max={max_score:.2f}")
            print(f"   🔄 Cloned: {num_cloned}/{MAX_AGENTS} agents (protected top {top_k})")
            if len(top_k_rewards) > 0:
                print(f"   🏆 Top {top_k} rewards: {top_k_rewards.tolist()}")
                print(f"       🏆 Best Agent Index: {top_agent_indices[0].item()}")
        else:
            print(f"🧬 FMC: No agents cloned (mean score: {scores.mean().item():.2f})")

    def _calculate_virtual_rewards(self, scores: torch.Tensor, agent_indices: torch.Tensor, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate virtual rewards based on scores and parameter distances."""
        # Calculate distances between agents and their partners
        dists = self._calculate_distances(agent_indices, partner_indices)
        
        # Relativize distances and scores
        rel_dists = self._relativize(dists)
        rel_scores = self._relativize(scores) ** FMC_BALANCE
        
        # Virtual rewards are the product of relativized scores and distances
        return rel_scores * rel_dists

    def _calculate_distances(self, agent_indices: torch.Tensor, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate Euclidean distances between agent parameters and their partners."""
        distances = torch.zeros(MAX_AGENTS, device=self.device)
        
        for module in self.model.modules():
            if isinstance(module, BatchedLinear):
                # Calculate distances for weights
                weight_diffs = module.weight[agent_indices] - module.weight[partner_indices]
                weight_distances = torch.norm(weight_diffs.view(MAX_AGENTS, -1), dim=1)
                distances += weight_distances
                
                # Calculate distances for biases if they exist
                if module.bias is not None:
                    bias_diffs = module.bias[agent_indices] - module.bias[partner_indices]
                    bias_distances = torch.norm(bias_diffs, dim=1)
                    distances += bias_distances
        
        return distances

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

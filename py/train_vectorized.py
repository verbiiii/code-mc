import torch
import numpy as np
from typing import Tuple
from batched_layers import BatchedLinear

MAX_AGENTS = 64

# FMC Constants
KEEP_TOP_PERCENT = 0.05
MUTATION_RATE = 1.0          # percent of weights that will be mutated
MUTATION_AMPLITUDE = 0.01    # maximum amplitude of the mutation (std dev for normal distribution)
FMC_BALANCE = 3.0

class VectorizedTrainer:
    def __init__(self, device='cpu'):
        self.device = torch.device(device)

        # Model with BatchedLinear layers - updated for pitch/yaw aiming + jump/sneak
        self.model = torch.nn.Sequential(
            BatchedLinear(MAX_AGENTS, 6, 64),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 64, 128),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 128, 64),
            torch.nn.Tanh(),
            BatchedLinear(MAX_AGENTS, 64, 36),  # [x(8) + y(8) + walk(1) + shoot(1) + jump(1) + sneak(1) + pitch(8) + yaw(8)]
        ).to(self.device)

        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=1e-3)

        self.rewards = torch.zeros((MAX_AGENTS, 1000), device=self.device)
        self.episode_lengths = torch.zeros(MAX_AGENTS, dtype=torch.long, device=self.device)
        self.reward_history = []

        # Fitness tracking
        self.round_cumulative_rewards = torch.zeros(MAX_AGENTS, device=self.device)  # Current round rewards
        self.lifetime_cumulative_rewards = torch.zeros(MAX_AGENTS, device=self.device)  # Never reset unless cloned
        self.best_agent_idx = 0  # Index of current lifetime champion

        print(f"🚀 RLAgents: {sum(p.numel() for p in self.model.parameters()):,} params on {device}")

    def forward_pass(self, observations: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, None]:
        logits = self.model(observations)
        x_logits = logits[:, :8]
        y_logits = logits[:, 8:16]
        walk_logits = logits[:, 16]
        shoot_logits = logits[:, 17]
        jump_logits = logits[:, 18]
        sneak_logits = logits[:, 19]
        pitch_logits = logits[:, 20:28]  # 8 categories for pitch (-90 to +90 degrees)
        yaw_logits = logits[:, 28:36]    # 8 categories for yaw (0 to 360 degrees)

        # Deterministic actions - take argmax instead of sampling
        x_actions = torch.argmax(x_logits, dim=1)
        y_actions = torch.argmax(y_logits, dim=1)
        walk_actions = (walk_logits > 0.0).bool()  # Deterministic threshold at 0
        shoot_actions = (shoot_logits > 0.0).bool()
        jump_actions = (jump_logits > 0.0).bool()
        sneak_actions = (sneak_logits > 0.0).bool()
        pitch_actions = torch.argmax(pitch_logits, dim=1)
        yaw_actions = torch.argmax(yaw_logits, dim=1)

        # No log probabilities for deterministic policies
        log_probs = None

        return x_actions, y_actions, walk_actions, shoot_actions, jump_actions, sneak_actions, pitch_actions, yaw_actions, log_probs

    def update_episode_data(self, agent_indices: torch.Tensor, reward_data: torch.Tensor, log_probs):
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
        self.round_cumulative_rewards[active_indices] += rewards
        self.lifetime_cumulative_rewards[active_indices] += rewards  # Also update lifetime rewards
        
        # Debug: Only print non-zero rewards
        non_zero_mask = rewards != 0
        if non_zero_mask.any():
            pass  # Removed individual agent reward prints for cleaner output
        
        # Note: log_probs is None for deterministic policies, so we don't store them

    def on_round_end(self):
        """Called at the end of each round."""
        # Update lifetime champion before applying FMC (which may cause cloning)
        self.update_lifetime_champion()
        
        self.apply_fmc_update()  # Apply FMC first while we still have cumulative rewards
        self.reset_cumulative_rewards()  # Then reset ONLY current round rewards
    
    def update_lifetime_champion(self):
        """Update tracking of lifetime champion based on lifetime cumulative rewards."""
        if torch.any(self.lifetime_cumulative_rewards > 0):
            current_lifetime_best_idx = torch.argmax(self.lifetime_cumulative_rewards).item()
            current_lifetime_best_reward = self.lifetime_cumulative_rewards[current_lifetime_best_idx].item()
            previous_champion_reward = self.lifetime_cumulative_rewards[self.best_agent_idx].item()
            
            if current_lifetime_best_idx != self.best_agent_idx:
                self.best_agent_idx = current_lifetime_best_idx
                print(f"🏆 NEW LIFETIME CHAMPION: Agent {current_lifetime_best_idx} with lifetime reward {current_lifetime_best_reward:.2f}")
            else:
                print(f"👑 Lifetime champion: Agent {self.best_agent_idx} (lifetime: {current_lifetime_best_reward:.2f}, this round: {self.round_cumulative_rewards[self.best_agent_idx].item():.2f})")

    def apply_fmc_update(self):
        """Apply FMC (Functional Mutation and Crossover) updates to the model parameters."""
            
        scores = self.round_cumulative_rewards.clone()
        # scores = self.lifetime_cumulative_rewards.clone()
        arange = torch.arange(MAX_AGENTS, device=self.device)
        
        # Select partners uniformly at random
        partner_indices = torch.randint(0, MAX_AGENTS, (MAX_AGENTS,), device=self.device)
        
        # Calculate virtual rewards
        vr = self._calculate_virtual_rewards(scores, arange, partner_indices)
        partner_vr = vr[partner_indices]
        
        # Determine cloning probability based on virtual rewards
        value = (partner_vr - vr) / torch.where(vr > 0, vr, torch.tensor(1e-8, device=self.device))
        
        # Random threshold for cloning decision
        r = torch.rand(MAX_AGENTS, device=self.device)
        will_clone = value >= r
        
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
            clone_indices_to_clone_from = partner_indices[will_clone]
            
            # Clone parameters for each BatchedLinear layer in the model
            for module in self.model.modules():
                if isinstance(module, BatchedLinear):
                    module.clone(will_clone, partner_indices)
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

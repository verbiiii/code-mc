
import torch
import numpy as np
import time
import sys
from typing import Dict, Tuple
from pathlib import Path

from observations import VectorizedObservations
from rl_operator import RLOperators
from metrics import get_random_experiment_name

USE_WANDB = True

# FMC Constants
USE_LIFETIME_REWARDS_FOR_TOPK = True
KEEP_TOP_PERCENT = 0.2
FMC_BALANCE = 3.0
SLOW_PROCESSING_THRESHOLD_MS = 5.0
BULLET_COST = 0.0  # Per-bullet penalty (only charged on ticks where damage was dealt)


class LiveStatusDisplay:
    """In-place terminal dashboard for training status."""

    def __init__(self, total_agents: int, min_refresh_seconds: float = 0.25):
        self.total_agents = total_agents
        self.min_refresh_seconds = min_refresh_seconds
        self.last_render_time = 0.0
        self.enabled = sys.stdout.isatty()
        # Track how many lines we printed last render so we can overwrite
        # only the dashboard block (without clearing the whole terminal).
        self._last_line_count = 0
        self._last_snapshot = None

        # Keep references to the original streams so we can draw the panel
        # without going through any wrappers (prevents recursion).
        self._raw_stdout = sys.stdout
        self._raw_stderr = sys.stderr
        self._installed = False
        self._prev_stdout = None
        self._prev_stderr = None

    def install_stream_interceptor(self):
        """Wrap stdout/stderr so other output doesn't corrupt the panel."""
        if not self.enabled or self._installed:
            return

        display = self

        class _StatusAwareStream:
            def __init__(self, raw_stream):
                self._raw = raw_stream

            def write(self, s):
                if not s:
                    return 0
                # If no panel has ever rendered, just pass through.
                if display._last_line_count <= 0:
                    return self._raw.write(s)

                # Clear current panel, print message, then redraw panel.
                display._clear_panel()
                n = self._raw.write(s)
                self._raw.flush()
                display._redraw_last_snapshot()
                return n

            def flush(self):
                return self._raw.flush()

            def isatty(self):
                return self._raw.isatty()

            @property
            def encoding(self):
                return getattr(self._raw, "encoding", None)

        self._prev_stdout = sys.stdout
        self._prev_stderr = sys.stderr
        sys.stdout = _StatusAwareStream(self._raw_stdout)
        sys.stderr = _StatusAwareStream(self._raw_stderr)
        self._installed = True

    def uninstall_stream_interceptor(self):
        if not self._installed:
            return
        if self._prev_stdout is not None:
            sys.stdout = self._prev_stdout
        if self._prev_stderr is not None:
            sys.stderr = self._prev_stderr
        self._installed = False

    def _clear_panel(self):
        if self._last_line_count <= 0:
            return
        # Move to top of panel, then clear from cursor to end of screen.
        self._raw_stdout.write(f"\x1b[{self._last_line_count}A")
        self._raw_stdout.write("\x1b[0J")
        self._raw_stdout.flush()

    def _redraw_last_snapshot(self):
        if self._last_snapshot is None:
            return
        # Force render regardless of throttling so logs don't leave the panel erased.
        self._render_snapshot(self._last_snapshot, force=True)

    def _render_snapshot(self, snapshot: dict, force: bool = False):
        now = time.perf_counter()
        if not force and now - self.last_render_time < self.min_refresh_seconds:
            return
        self.last_render_time = now
        self._last_snapshot = snapshot

        lines = [
            f"Ticks: {snapshot['tick_count']:,} | FMC updates: {snapshot['fmc_updates']:,} | Rounds: {snapshot['rounds']:,}",
            f"Agents: active {snapshot['active_agents']}/{self.total_agents} | cloned(last) {snapshot['last_cloned']}/{self.total_agents}",
            (
                f"Processing ms: now {snapshot['processing_now_ms']:.2f} | avg100 {snapshot['processing_avg_ms']:.2f} "
                f"| p95 {snapshot['processing_p95_ms']:.2f} | slow>{SLOW_PROCESSING_THRESHOLD_MS:.1f}ms "
                f"{snapshot['slow_tick_count']}/{snapshot['tick_count']:,}"
            ),
            f"Steps behind: {snapshot['steps_behind']} | total skipped steps: {snapshot['total_skipped_steps']}",
            (
                f"Scores: mean {snapshot['score_mean']:.2f} | std {snapshot['score_std']:.2f} | "
                f"max {snapshot['score_max']:.2f} | best agent #{snapshot['best_agent_index']}"
            ),
            f"Top rewards: {snapshot['top_rewards']}",
            f"Top lifetimes: {snapshot['top_lifetimes']}",
        ]

        if self.enabled:
            # Always erase the entire previous panel block before drawing.
            if self._last_line_count > 0:
                self._raw_stdout.write(f"\x1b[{self._last_line_count}A")
            self._raw_stdout.write("\x1b[0J")
            for line in lines:
                self._raw_stdout.write("\x1b[2K\r" + line + "\n")
            self._raw_stdout.flush()
            self._last_line_count = len(lines)
        else:
            print(" | ".join(lines))

    def render(self, snapshot: dict):
        self._render_snapshot(snapshot, force=False)

class VectorizedTrainer:
    def __init__(self, device='cpu', num_agents: int = 32):
        self.num_agents = num_agents
        self.device = torch.device(device)
        # Keep the operator forward pass on GPU when available (see `RLOperators.DEFAULT_DEVICE`),
        # while allowing the trainer bookkeeping to remain on `self.device` (default CPU).
        self.operators = RLOperators(num_agents=self.num_agents)
        self.reward_history = []
        self.num_updates = 0
        self.fmc_update_count = 0
        self.tick_count = 0
        self.round_count = 0

        # Fitness tracking
        self.round_cumulative_rewards = torch.zeros(num_agents, device=self.device)  # Current round rewards
        # Per-slot scalars: accumulate across rounds; zero when this slot is cloned (new policy).
        self.lifetime_cumulative_rewards = torch.zeros(num_agents, device=self.device)
        self.processing_times_ms = []
        self.slow_tick_count = 0
        self.latest_obs_active_agents = 0
        self.latest_steps_behind = 0
        self.total_skipped_steps = 0
        self.latest_fmc = {
            "num_cloned": 0,
            "mean_score": 0.0,
            "std_score": 0.0,
            "max_score": 0.0,
            "best_agent_index": 0,
            "top_rewards": [],
            "top_lifetimes": [],
        }
        # Synced to Java after each FMC round for nametag crowns (elite indices in rank order).
        self.last_elite_indices: list = []
        self.status_display = LiveStatusDisplay(total_agents=num_agents)
        self.status_display.install_stream_interceptor()

        self.experiment_name = get_random_experiment_name()
        self.wandb_url = None
        self._init_wandb()
        self.checkpoint_root = Path("./checkpoints")
        self.checkpoint_run_name = self.experiment_name
        self.checkpoint_dir = self.checkpoint_root / self.checkpoint_run_name

        print(f"🚀 RLAgents: {sum(p.numel() for p in self.operators.parameters()):,} params on {self.operators.device}")

    def _init_wandb(self):
        if not USE_WANDB:
            return
        try:
            import wandb

            run = wandb.init(
                project="minekov-rl",
                name=self.experiment_name,
                config={"num_agents": self.num_agents},
            )
            self.wandb_url = getattr(run, "url", None)
        except Exception as e:
            print(f"W&B init failed (training continues): {e}")
            self.wandb_url = None

    def tick(self, observations: VectorizedObservations):
        self.tick_count += 1
        self.calculate_rewards(observations)
        return self.forward(observations)

    def _wandb_log_entropy(self, entropy_by_component: Dict[str, torch.Tensor]) -> None:
        if not USE_WANDB:
            return
        try:
            import wandb

            if wandb.run is None:
                return
            # One value per agent: mean entropy across policy components.
            stacked = torch.stack([t.float() for t in entropy_by_component.values()], dim=0)
            per_agent = stacked.mean(dim=0)
            wandb.log(
                {
                    "entropy/min": per_agent.min().item(),
                    "entropy/max": per_agent.max().item(),
                    "entropy/avg": per_agent.mean().item(),
                },
                step=self.tick_count,
            )
        except Exception:
            pass

    def forward(self, observations: VectorizedObservations):
        """
        observations: [N, 3] – only alive agents
        agent_indices: [N] – indices of those agents (0 <= index < MAX_AGENTS)
        group_indices: [N] – group id per agent (used to match group-mates)
        """
        sample = self.operators.sample_policy(observations)
        self._wandb_log_entropy(sample.entropy_by_component)

        return (
            sample.move_w_actions,
            sample.move_a_actions,
            sample.move_s_actions,
            sample.move_d_actions,
            sample.shoot_actions,
            sample.jump_actions,
            sample.sneak_actions,
            sample.pitch_actions,
            sample.yaw_actions,
        )

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

        self.current_rewards = torch.zeros(active_mask.sum(), device=self.device, dtype=torch.float32)
        self.current_rewards += (dmg_dealt * 1.0) + (kills * 10)
        self.current_rewards -= (dmg_taken * 1.0) + (deaths * 10.0)
        self.current_rewards -= (num_bullets * BULLET_COST) * (dmg_dealt > 0).float()

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
        self.round_count += 1
        self.apply_fmc_update()
        self.reset_cumulative_rewards()

    def apply_fmc_update(self):
        """Apply FMC (Functional Mutation and Crossover) updates to the model parameters."""
        self.fmc_update_count += 1

        ops_device = self.operators.device

        # print("This Round's Cumulative Rewards:")
        # print(self.round_cumulative_rewards)

        # scores = self.current_rewards.clone()
        scores = self.round_cumulative_rewards.clone().to(ops_device)
        if USE_LIFETIME_REWARDS_FOR_TOPK:
            metric_for_top_k = self.lifetime_cumulative_rewards.clone().to(ops_device)
        else:
            metric_for_top_k = scores.clone()

        # # Select partners with fitness bias so strong policies actually propagate.
        # # (Uniform random partners tends to stall: only protected elites stay good.)
        # fitness = scores - scores.min()
        # if float(fitness.sum().item()) <= 0.0:
        #     partner_indices = torch.randint(0, self.num_agents, (self.num_agents,), device=ops_device)
        # else:
        #     probs = (fitness + 1e-6) / (fitness.sum() + 1e-6 * self.num_agents)
        #     partner_indices = torch.multinomial(probs, self.num_agents, replacement=True)
        partner_indices = torch.randint(0, self.num_agents, (self.num_agents,), device=ops_device)

        # distance_partner_is = torch.multinomial(normalized_scores, MAX_AGENTS, replacement=True)

        # Calculate virtual rewards (also keep raw / relativized distances for logging)
        vr, dists, rel_dists = self._calculate_virtual_rewards(scores, partner_indices)
        partner_vr = vr[partner_indices]

        self._wandb_log_fmc(scores, vr, dists, rel_dists)

        # Determine cloning probability based on virtual rewards
        value = (partner_vr - vr) / torch.where(vr > 0, vr, torch.tensor(1e-8, device=ops_device))
        
        # Random threshold for cloning decision
        r = torch.rand(self.num_agents, device=ops_device)
        will_clone = value >= r

        # Protect top agents from being cloned (they keep their parameters)
        top_k = max(int(self.num_agents * KEEP_TOP_PERCENT), 1)
        if top_k <= 0:
            raise ValueError("KEEP_TOP_PERCENT must be greater than 0 to protect at least one agent.")

        top_agent_indices = torch.topk(metric_for_top_k, top_k).indices
        self.last_elite_indices = [int(x) for x in top_agent_indices.cpu().tolist()]
        will_clone[top_agent_indices] = False

        # will_perturbate = torch.ones(self.num_agents, device=self.device, dtype=torch.bool)
        # will_perturbate[top_agent_indices] = False  # Don't perturb top agents
        will_perturbate = will_clone.clone()  # Perturb all cloned agents

        # Get top k rewards for display
        top_k_rewards = scores[top_agent_indices] if top_k > 0 else torch.tensor([])

        top_k_lifetime_rewards = self.lifetime_cumulative_rewards.to(ops_device)[top_agent_indices] if top_k > 0 else torch.tensor([])

        self.operators.blend_parameters(partner_indices, will_clone, will_perturbate)

        will_clone_cpu = will_clone.cpu()
        self.round_cumulative_rewards[will_clone_cpu] = 0.0
        self.lifetime_cumulative_rewards[will_clone_cpu] = 0.0
        
        # Enhanced FMC metrics
        num_cloned = will_clone.sum().item()
        mean_score = scores.mean().item()
        std_score = scores.std().item()
        max_score = scores.max().item()
        best_agent_index = top_agent_indices[0].item() if len(top_agent_indices) > 0 else 0

        self.latest_fmc = {
            "num_cloned": num_cloned,
            "mean_score": mean_score,
            "std_score": std_score,
            "max_score": max_score,
            "best_agent_index": best_agent_index,
            "top_rewards": [round(float(x), 2) for x in top_k_rewards.tolist()],
            "top_lifetimes": [round(float(x), 2) for x in top_k_lifetime_rewards.tolist()],
        }

        checkpoint_path = self.operators.save_checkpoint(
            checkpoint_dir=str(self.checkpoint_dir),
            fmc_update=self.fmc_update_count,
        )
        print(f"💾 Saved FMC checkpoint: {checkpoint_path}")

    def update_runtime_status(self, processing_time_ms: float, active_agents: int, steps_behind: int = 0, total_skipped_steps: int = 0):
        self.processing_times_ms.append(float(processing_time_ms))
        if len(self.processing_times_ms) > 100:
            self.processing_times_ms.pop(0)
        if processing_time_ms > SLOW_PROCESSING_THRESHOLD_MS:
            self.slow_tick_count += 1

        self.latest_obs_active_agents = int(active_agents)
        self.latest_steps_behind = int(steps_behind)
        self.total_skipped_steps = int(total_skipped_steps)
        self._render_status()

    def _render_status(self):
        if self.processing_times_ms:
            processing_avg_ms = float(np.mean(self.processing_times_ms))
            processing_p95_ms = float(np.percentile(self.processing_times_ms, 95))
            processing_now_ms = float(self.processing_times_ms[-1])
        else:
            processing_avg_ms = 0.0
            processing_p95_ms = 0.0
            processing_now_ms = 0.0

        snapshot = {
            "tick_count": self.tick_count,
            "fmc_updates": self.fmc_update_count,
            "rounds": self.round_count,
            "active_agents": self.latest_obs_active_agents,
            "last_cloned": self.latest_fmc["num_cloned"],
            "processing_now_ms": processing_now_ms,
            "processing_avg_ms": processing_avg_ms,
            "processing_p95_ms": processing_p95_ms,
            "slow_tick_count": self.slow_tick_count,
            "steps_behind": self.latest_steps_behind,
            "total_skipped_steps": self.total_skipped_steps,
            "score_mean": self.latest_fmc["mean_score"],
            "score_std": self.latest_fmc["std_score"],
            "score_max": self.latest_fmc["max_score"],
            "best_agent_index": self.latest_fmc["best_agent_index"],
            "top_rewards": self.latest_fmc["top_rewards"],
            "top_lifetimes": self.latest_fmc["top_lifetimes"],
        }
        self.status_display.render(snapshot)

    def _wandb_log_fmc(
        self,
        scores: torch.Tensor,
        vr: torch.Tensor,
        dists: torch.Tensor,
        rel_dists: torch.Tensor,
    ):
        if not USE_WANDB:
            return
        try:
            import wandb

            if wandb.run is None:
                return
            wandb.log(
                {
                    "reward/average_reward": scores.mean().item(),
                    "reward/min_reward": scores.min().item(),
                    "reward/max_reward": scores.max().item(),
                    "virtual_reward/average_reward": vr.mean().item(),
                    "virtual_reward/min_reward": vr.min().item(),
                    "virtual_reward/max_reward": vr.max().item(),
                    "distance/raw_mean": dists.mean().item(),
                    "distance/raw_min": dists.min().item(),
                    "distance/raw_max": dists.max().item(),
                    "distance/rel_mean": rel_dists.mean().item(),
                    "distance/rel_min": rel_dists.min().item(),
                    "distance/rel_max": rel_dists.max().item(),
                },
                # Must use the same monotonic step as per-tick logs; `fmc_update_count` lags `tick_count`
                # and would rewind the x-axis, so reward/distance metrics never appeared in W&B.
                step=self.tick_count,
            )
        except Exception:
            pass

    def _calculate_virtual_rewards(
        self, scores: torch.Tensor, partner_indices: torch.Tensor
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Virtual rewards from relativized scores and partner parameter distances."""
        dists = self.operators.calculate_distances(partner_indices)
        rel_dists = self._relativize(dists)
        rel_scores = self._relativize(scores) ** FMC_BALANCE
        vr = rel_scores * rel_dists
        return vr, dists, rel_dists

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
        """Get indices of top-k agents by round or lifetime cumulative rewards (see USE_LIFETIME_REWARDS_FOR_TOPK)."""
        metric = (
            self.lifetime_cumulative_rewards
            if USE_LIFETIME_REWARDS_FOR_TOPK
            else self.round_cumulative_rewards
        )
        if metric.numel() == 0:
            return torch.tensor([], device=self.device, dtype=torch.long)

        top_k_values, top_k_indices = torch.topk(metric, k, sorted=True)
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

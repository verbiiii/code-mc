import torch
from dataclasses import dataclass
from typing import Dict, Tuple
from pathlib import Path
from typing import Optional

from torch.distributions import Normal

from batched_linear import BatchedLinear, BatchedNNModule
from batched_attention import BatchedCrossAttention
from observations import VectorizedObservations


# maximum amplitude of the mutation (std dev for normal distribution)
MUTATION_AMPLITUDE = 0.1


# Prefer GPU when available for operator forward passes.
# def _detect_device():
#     if torch.cuda.is_available():
#         try:
#             torch.zeros(1, device="cuda")
#             return torch.device("cuda")
#         except RuntimeError as e:
#             print(f"WARNING: CUDA unavailable ({e}), falling back to CPU")
#     return torch.device("cpu")

# DEFAULT_DEVICE = _detect_device()
DEFAULT_DEVICE = torch.device("cpu")


def _split_policy_means_stds(y: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
    """y: [N, 46] -> means [N, 23], log_std [N, 23]."""
    mean = y[:, :23]
    log_std = y[:, 23:]
    return mean, log_std


def _std_from_log(log_std: torch.Tensor, exploration_scale: float) -> torch.Tensor:
    scale = torch.exp(log_std)
    es = max(float(exploration_scale), 1e-6)
    return scale * es


@dataclass
class PolicyDistributions:
    """Separable Normal distributions; entropy sums independently per group."""

    move_w: Normal
    move_a: Normal
    move_s: Normal
    move_d: Normal
    shoot: Normal
    jump: Normal
    sneak: Normal
    pitch: Normal
    yaw: Normal

    @classmethod
    def from_raw_output(cls, y: torch.Tensor, exploration_scale: float = 1.0) -> "PolicyDistributions":
        mean, log_std = _split_policy_means_stds(y)
        std = _std_from_log(log_std, exploration_scale)

        m_w = mean[:, 0]
        m_a = mean[:, 1]
        m_s = mean[:, 2]
        m_d = mean[:, 3]
        m_shoot = mean[:, 4]
        m_jump = mean[:, 5]
        m_sneak = mean[:, 6]
        m_pitch = mean[:, 7:15]
        m_yaw = mean[:, 15:23]

        s_w = std[:, 0]
        s_a = std[:, 1]
        s_s = std[:, 2]
        s_d = std[:, 3]
        s_shoot = std[:, 4]
        s_jump = std[:, 5]
        s_sneak = std[:, 6]
        s_pitch = std[:, 7:15]
        s_yaw = std[:, 15:23]

        return cls(
            move_w=Normal(m_w, s_w),
            move_a=Normal(m_a, s_a),
            move_s=Normal(m_s, s_s),
            move_d=Normal(m_d, s_d),
            shoot=Normal(m_shoot, s_shoot),
            jump=Normal(m_jump, s_jump),
            sneak=Normal(m_sneak, s_sneak),
            pitch=Normal(m_pitch, s_pitch),
            yaw=Normal(m_yaw, s_yaw),
        )

    def entropy_by_component(self) -> Dict[str, torch.Tensor]:
        """Per-agent entropy [N] for each separable group."""
        return {
            "move_w": self.move_w.entropy(),
            "move_a": self.move_a.entropy(),
            "move_s": self.move_s.entropy(),
            "move_d": self.move_d.entropy(),
            "shoot": self.shoot.entropy(),
            "jump": self.jump.entropy(),
            "sneak": self.sneak.entropy(),
            "pitch": self.pitch.entropy().sum(dim=-1),
            "yaw": self.yaw.entropy().sum(dim=-1),
        }

    def sample_actions(
        self,
    ) -> Tuple[
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
        torch.Tensor,
    ]:
        move_w_actions = self.move_w.rsample() > 0.0
        move_a_actions = self.move_a.rsample() > 0.0
        move_s_actions = self.move_s.rsample() > 0.0
        move_d_actions = self.move_d.rsample() > 0.0
        shoot_actions = self.shoot.rsample() > 0.0
        jump_actions = self.jump.rsample() > 0.0
        sneak_actions = self.sneak.rsample() > 0.0

        pitch_s = self.pitch.rsample()
        yaw_s = self.yaw.rsample()
        pitch_actions = pitch_s.argmax(dim=-1)
        yaw_actions = yaw_s.argmax(dim=-1)

        return (
            move_w_actions,
            move_a_actions,
            move_s_actions,
            move_d_actions,
            shoot_actions,
            jump_actions,
            sneak_actions,
            pitch_actions,
            yaw_actions,
        )


@dataclass
class PolicySample:
    move_w_actions: torch.Tensor
    move_a_actions: torch.Tensor
    move_s_actions: torch.Tensor
    move_d_actions: torch.Tensor
    shoot_actions: torch.Tensor
    jump_actions: torch.Tensor
    sneak_actions: torch.Tensor
    pitch_actions: torch.Tensor
    yaw_actions: torch.Tensor
    distributions: PolicyDistributions
    entropy_by_component: Dict[str, torch.Tensor]


class RLOperators(torch.nn.Module):
    def __init__(self, device=None, num_agents: int = 32):
        super(RLOperators, self).__init__()

        self.num_agents = num_agents
        self._device = DEFAULT_DEVICE if device is None else torch.device(device)

        self.input_features = 13
        self.hidden_dim = 16

        self.model = torch.nn.Sequential(
            BatchedCrossAttention(num_agents, self.input_features, self.hidden_dim, hidden_dim=self.hidden_dim),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, self.hidden_dim, self.hidden_dim),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, self.hidden_dim, self.hidden_dim),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, self.hidden_dim, self.hidden_dim),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, self.hidden_dim, 46),
        ).to(self.device)

    @property
    def device(self) -> torch.device:
        return self._device

    def forward(self, observations: VectorizedObservations) -> torch.Tensor:
        x = observations.tensorized().to(self.device)  # [num_agents, 13]
        num_agents = self.num_agents

        batch_observations = x.unsqueeze(1).expand(-1, num_agents, -1).clone()
        batch_observations[:, :, 0] = 0.0

        agent_indices = observations.agent_indices.to(self.device)
        batch_observations[agent_indices, agent_indices, 0] = 1.0

        y = self.model.forward(batch_observations)
        return y

    def policy_distributions(
        self, observations: VectorizedObservations, exploration_scale: float = 1.0
    ) -> PolicyDistributions:
        y = self.forward(observations)
        return PolicyDistributions.from_raw_output(y, exploration_scale=exploration_scale)

    def sample_policy(
        self, observations: VectorizedObservations, exploration_scale: float = 1.0
    ) -> PolicySample:
        y = self.forward(observations)
        dists = PolicyDistributions.from_raw_output(y, exploration_scale=exploration_scale)
        entropy_by_component = dists.entropy_by_component()
        (
            move_w_actions,
            move_a_actions,
            move_s_actions,
            move_d_actions,
            shoot_actions,
            jump_actions,
            sneak_actions,
            pitch_actions,
            yaw_actions,
        ) = dists.sample_actions()
        return PolicySample(
            move_w_actions=move_w_actions,
            move_a_actions=move_a_actions,
            move_s_actions=move_s_actions,
            move_d_actions=move_d_actions,
            shoot_actions=shoot_actions,
            jump_actions=jump_actions,
            sneak_actions=sneak_actions,
            pitch_actions=pitch_actions,
            yaw_actions=yaw_actions,
            distributions=dists,
            entropy_by_component=entropy_by_component,
        )

    def blend_parameters(self, partner_indices: torch.Tensor, will_clone: torch.Tensor, will_perturbate: torch.Tensor = None):
        if will_perturbate is None:
            will_perturbate = will_clone.clone()

        for module in self.modules():
            if isinstance(module, BatchedNNModule):
                module.clone(will_clone, partner_indices)
                module.mutate(will_perturbate, MUTATION_AMPLITUDE)

    def calculate_distances(self, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate Euclidean distances between agent parameters and their partners."""
        distances = torch.zeros(self.num_agents, device=self.device)

        if partner_indices.shape[0] != self.num_agents:
            raise ValueError(f"partner_indices must have shape ({self.num_agents},), got {partner_indices.shape}")

        for module in self.modules():
            if isinstance(module, BatchedNNModule):
                distances += module.calculate_distances(partner_indices)

        return distances

    @torch.no_grad()
    def save_checkpoint(self, checkpoint_dir: str, fmc_update: int, filename: Optional[str] = None) -> str:
        """Save only this module's state_dict and return the written path."""
        out_dir = Path(checkpoint_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        if filename is None:
            filename = f"{int(fmc_update)}.pth"

        output_path = out_dir / filename
        torch.save(self.state_dict(), output_path)
        return str(output_path.resolve())

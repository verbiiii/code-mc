import torch
from dataclasses import dataclass
from typing import Dict, Tuple

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
    """y: [N, 56] → means [N, 28], log_std [N, 28]."""
    mean = y[:, :28]
    log_std = y[:, 28:]
    return mean, log_std


def _std_from_log(log_std: torch.Tensor, exploration_scale: float) -> torch.Tensor:
    scale = torch.exp(log_std)
    es = max(float(exploration_scale), 1e-6)
    return scale * es


@dataclass
class PolicyDistributions:
    """Separable Normal distributions; entropy sums independently per group."""

    movement: Normal
    walk: Normal
    shoot: Normal
    jump: Normal
    sneak: Normal
    pitch: Normal
    yaw: Normal

    @classmethod
    def from_raw_output(cls, y: torch.Tensor, exploration_scale: float = 1.0) -> "PolicyDistributions":
        mean, log_std = _split_policy_means_stds(y)
        std = _std_from_log(log_std, exploration_scale)

        m_mov = mean[:, :8]
        s_mov = std[:, :8]
        m_walk = mean[:, 8]
        m_shoot = mean[:, 9]
        m_jump = mean[:, 10]
        m_sneak = mean[:, 11]
        m_pitch = mean[:, 12:20]
        m_yaw = mean[:, 20:28]

        s_walk = std[:, 8]
        s_shoot = std[:, 9]
        s_jump = std[:, 10]
        s_sneak = std[:, 11]
        s_pitch = std[:, 12:20]
        s_yaw = std[:, 20:28]

        return cls(
            movement=Normal(m_mov, s_mov),
            walk=Normal(m_walk, s_walk),
            shoot=Normal(m_shoot, s_shoot),
            jump=Normal(m_jump, s_jump),
            sneak=Normal(m_sneak, s_sneak),
            pitch=Normal(m_pitch, s_pitch),
            yaw=Normal(m_yaw, s_yaw),
        )

    def entropy_by_component(self) -> Dict[str, torch.Tensor]:
        """Per-agent entropy [N] for each separable group (movement/pitch/yaw summed over discrete dims)."""
        return {
            "movement": self.movement.entropy().sum(dim=-1),
            "walk": self.walk.entropy(),
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
    ]:
        mov = self.movement.rsample()
        movement_theta = mov.argmax(dim=-1)

        walk_actions = self.walk.rsample() > 0.0
        shoot_actions = self.shoot.rsample() > 0.0
        jump_actions = self.jump.rsample() > 0.0
        sneak_actions = self.sneak.rsample() > 0.0

        pitch_s = self.pitch.rsample()
        yaw_s = self.yaw.rsample()
        pitch_actions = pitch_s.argmax(dim=-1)
        yaw_actions = yaw_s.argmax(dim=-1)

        return (
            movement_theta,
            walk_actions,
            shoot_actions,
            jump_actions,
            sneak_actions,
            pitch_actions,
            yaw_actions,
        )


@dataclass
class PolicySample:
    movement_theta: torch.Tensor
    walk_actions: torch.Tensor
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
            BatchedLinear(num_agents, self.hidden_dim, 56),
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
            movement_theta,
            walk_actions,
            shoot_actions,
            jump_actions,
            sneak_actions,
            pitch_actions,
            yaw_actions,
        ) = dists.sample_actions()
        return PolicySample(
            movement_theta=movement_theta,
            walk_actions=walk_actions,
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

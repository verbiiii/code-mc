import torch
from batched_linear import BatchedLinear, BatchedNNModule
from batched_attention import BatchedCrossAttention
from observations import VectorizedObservations


# maximum amplitude of the mutation (std dev for normal distribution)
MUTATION_AMPLITUDE = 0.1


class RLOperators(torch.nn.Module):
    def __init__(self, device='cpu', num_agents: int = 32):
        super(RLOperators, self).__init__()

        self.num_agents = num_agents
        self.device = torch.device(device)

        self.input_features = 13
        self.hidden_dim = 32

        # Model with BatchedLinear layers - updated for pitch/yaw aiming + jump/sneak
        self.model = torch.nn.Sequential(
            # BatchedLinear(num_agents, self.input_features, 32),
            BatchedCrossAttention(num_agents, self.input_features, 32, hidden_dim=self.hidden_dim),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, 32, 32),
            torch.nn.Sigmoid(),
            BatchedLinear(num_agents, 32, 28),  # [theta(8) + walk(1) + shoot(1) + jump(1) + sneak(1) + pitch(8) + yaw(8)]
        ).to(self.device)

    def forward(self, observations: VectorizedObservations):
        x = observations.tensorized()  # [num_agents, 12]
        num_agents = self.num_agents

        # Duplicate to [num_agents, num_agents, 12]
        batch_observations = x.unsqueeze(1).expand(-1, num_agents, -1).clone()

        # Zero the 0th feature
        batch_observations[:, :, 0] = 0.0

        # Set one-hot indicator: [num_agents, num_agents], diagonal = 1.0
        batch_observations[observations.agent_indices, observations.agent_indices, 0] = 1.0

        y = self.model.forward(batch_observations)
        return y

    def blend_parameters(self, partner_indices: torch.Tensor, will_clone: torch.Tensor, will_perturbate: torch.Tensor = None):
        if will_perturbate is None:
            will_perturbate = will_clone.clone()

        # Perform cloning and perturbation
        for module in self.modules():
            if isinstance(module, BatchedNNModule):
                # IMPORTANT: true cloning (copy winner -> loser). Blending here causes
                # the best policies to not actually propagate through the population.
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
import torch
from batched_linear import BatchedLinear
from observations import VectorizedObservations


# maximum amplitude of the mutation (std dev for normal distribution)
MUTATION_AMPLITUDE = 0.01


class RLOperators(torch.nn.Module):
    def __init__(self, device='cpu', num_agents: int = 32, embedding_size: int = 8):
        super(RLOperators, self).__init__()

        self.num_agents = num_agents
        self.device = torch.device(device)
        self.input_features = 3 + ((num_agents - 1) * 4)

        # # Model with BatchedLinear layers - updated for pitch/yaw aiming + jump/sneak
        # self.model = torch.nn.Sequential(
        #     BatchedLinear(num_agents, self.input_features, 32),
        #     torch.nn.Tanh(),
        #     BatchedLinear(num_agents, 32, 64),
        #     torch.nn.Tanh(),
        #     BatchedLinear(num_agents, 64, 64),
        #     torch.nn.Tanh(),
        #     BatchedLinear(num_agents, 64, 32),
        #     torch.nn.Tanh(),
        #     BatchedLinear(num_agents, 32, 28),  # [theta(8) + walk(1) + shoot(1) + jump(1) + sneak(1) + pitch(8) + yaw(8)]
        # ).to(self.device)

        self.positions_model = BatchedLinear(num_agents, 3, 32).to(self.device)

    def forward(self, observations: VectorizedObservations):
        # let's zero-pad all of the agent indices that are missing
        # padded_x = torch.zeros((self.num_agents, self.input_features), device=self.device)        
        # padded_x[agent_indices] = x
        # return self.model(padded_x)
        
        print(observations.tensorized())
        print(observations.tensorized().shape)
        raise NotImplementedError
    
    def blend_parameters(self, partner_indices: torch.Tensor, will_clone: torch.Tensor, will_perturbate: torch.Tensor = None):
        if will_perturbate is None:
            will_perturbate = will_clone.clone()

        # Perform cloning and perturbation
        for module in self.modules():
            if isinstance(module, BatchedLinear):
                # module.clone(will_clone, partner_indices)
                module.blend(will_clone, partner_indices)

                # Mutate the cloned parameters
                module.mutate(will_perturbate, MUTATION_AMPLITUDE)

    def calculate_distances(self, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate Euclidean distances between agent parameters and their partners."""
        distances = torch.zeros(self.num_agents, device=self.device)

        if partner_indices.shape[0] != self.num_agents:
            raise ValueError(f"partner_indices must have shape ({self.num_agents},), got {partner_indices.shape}")

        for module in self.modules():
            if isinstance(module, BatchedLinear):
                # Calculate distances for weights
                weight_diffs = module.weight - module.weight[partner_indices]
                weight_distances = torch.norm(weight_diffs.view(self.num_agents, -1), dim=1)
                distances += weight_distances
                
                # Calculate distances for biases if they exist
                if module.bias is not None:
                    bias_diffs = module.bias - module.bias[partner_indices]
                    bias_distances = torch.norm(bias_diffs, dim=1)
                    distances += bias_distances
        
        return distances
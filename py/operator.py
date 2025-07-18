import torch
from batched_linear import BatchedLinear
from observations import VectorizedObservations


class RLOperator(torch.nn.Module):
    def __init__(self, device='cpu', num_agents: int = 32):
        super(RLOperator, self).__init__()

        self.num_agents = num_agents
        self.device = torch.device(device)
        self.input_features = 3 + ((num_agents - 1) * 4)

        # Model with BatchedLinear layers - updated for pitch/yaw aiming + jump/sneak
        self.model = torch.nn.Sequential(
            BatchedLinear(num_agents, self.input_features, 32),
            torch.nn.Tanh(),
            BatchedLinear(num_agents, 32, 64),
            torch.nn.Tanh(),
            BatchedLinear(num_agents, 64, 64),
            torch.nn.Tanh(),
            BatchedLinear(num_agents, 64, 32),
            torch.nn.Tanh(),
            BatchedLinear(num_agents, 32, 28),  # [theta(8) + walk(1) + shoot(1) + jump(1) + sneak(1) + pitch(8) + yaw(8)]
        ).to(self.device)

    def forward(self, observations: VectorizedObservations):
        # let's zero-pad all of the agent indices that are missing
        # padded_x = torch.zeros((self.num_agents, self.input_features), device=self.device)        
        # padded_x[agent_indices] = x
        # return self.model(padded_x)
        raise NotImplementedError
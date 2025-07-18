import torch
import torch.nn as nn
import torch.nn.functional as F
from batched_linear import BatchedLinear, BatchedNNModule


class BatchedCrossAttention(BatchedNNModule):
    def __init__(self, num_agents, in_features, out_features, hidden_dim):
        super().__init__(num_agents)
        self.num_agents = num_agents
        self.query_proj = BatchedLinear(num_agents, in_features, hidden_dim)
        self.key_proj   = BatchedLinear(num_agents, in_features, hidden_dim, bias=False)
        self.value_proj = BatchedLinear(num_agents, in_features, out_features, bias=False)
        self.scale = hidden_dim ** 0.5

    def forward(self, x):
        """
        x: [num_agents, num_agents, in_features]
        Returns: [num_agents, 1, out_features]
        """
        B, N, D = x.shape  # B == num_agents

        # Compute per-agent query from each agent's self observation
        diag_indices = torch.arange(B, device=x.device)
        self_obs = x[diag_indices, diag_indices]  # [B, in_features]

        q = self.query_proj(self_obs).unsqueeze(1)  # [B, 1, H]
        k = self.key_proj(x)     # [B, N, H]
        v = self.value_proj(x)   # [B, N, O]

        # Scaled dot product attention
        attn_scores = torch.matmul(q, k.transpose(1, 2)) / self.scale  # [B, 1, N]
        attn_weights = F.softmax(attn_scores, dim=-1)  # [B, 1, N]

        out = torch.matmul(attn_weights, v)  # [B, 1, O]
        return out.squeeze(1)  # (so we squeeze it)

    @torch.no_grad()
    def clone(self, clone_mask: torch.Tensor, clone_indices: torch.Tensor):
        """
        clone_mask: Bool or int tensor of shape (N,)
        clone_indices: Int tensor of shape (N,)
        """

        self.query_proj.clone(clone_mask, clone_indices)
        self.key_proj.clone(clone_mask, clone_indices)
        self.value_proj.clone(clone_mask, clone_indices)

    @torch.no_grad()
    def blend(self, mask: torch.Tensor, indices: torch.Tensor):
        """Combine the parameters of the two sets of weights and biases using the provided mask and indices."""
        
        self.query_proj.blend(mask, indices)
        self.key_proj.blend(mask, indices)
        self.value_proj.blend(mask, indices)
                              
    @torch.no_grad()
    def mutate(self, mutation_mask: torch.Tensor, noise_std: float = 0.01):
        # Apply mutation to the query, key, and value projections
        
        self.query_proj.mutate(mutation_mask, noise_std)
        self.key_proj.mutate(mutation_mask, noise_std)
        self.value_proj.mutate(mutation_mask, noise_std)

    @torch.no_grad()
    def calculate_distances(self, partner_indices):
        distances = self.query_proj.calculate_distances(partner_indices)
        distances += self.key_proj.calculate_distances(partner_indices)
        distances += self.value_proj.calculate_distances(partner_indices)
        return distances
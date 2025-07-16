import torch
import torch.nn as nn
from batched_linear import BatchedLinear


class BatchedAttention(nn.Module):
    def __init__(self, batch_size, embed_dim, num_heads):
        super().__init__()
        assert embed_dim % num_heads == 0, "embed_dim must be divisible by num_heads"
        
        self.batch_size = batch_size
        self.embed_dim = embed_dim
        self.num_heads = num_heads
        self.head_dim = embed_dim // num_heads

        # Each agent gets its own weights via BatchedLinear
        self.q_proj = BatchedLinear(batch_size, embed_dim, embed_dim)
        self.k_proj = BatchedLinear(batch_size, embed_dim, embed_dim)
        self.v_proj = BatchedLinear(batch_size, embed_dim, embed_dim)
        self.out_proj = BatchedLinear(batch_size, embed_dim, embed_dim)

    def forward(self, x: torch.Tensor, mask: torch.Tensor = None):
        """
        x: [B, N, D] - for each agent in batch, N neighbor observations of dimension D
        mask: [B, N] - 0 for padding, 1 for real
        """

        B, N, D = x.shape
        assert B == self.batch_size, "BatchedAttention expects fixed batch size"

        # Flatten inputs to shape [B*N, D] and tile batch indices
        x_flat = x.view(B * N, D)
        batch_indices = torch.arange(B, device=x.device).repeat_interleave(N)

        # Project to Q, K, V using per-agent weights
        q = self.q_proj(x_flat, batch_indices).view(B, N, self.num_heads, self.head_dim).transpose(1, 2)  # [B, H, N, D_head]
        k = self.k_proj(x_flat, batch_indices).view(B, N, self.num_heads, self.head_dim).transpose(1, 2)
        v = self.v_proj(x_flat, batch_indices).view(B, N, self.num_heads, self.head_dim).transpose(1, 2)

        # Scaled dot-product attention
        scores = torch.matmul(q, k.transpose(-2, -1)) / (self.head_dim ** 0.5)  # [B, H, N, N]

        if mask is not None:
            mask = mask[:, None, None, :]  # [B, 1, 1, N]
            scores = scores.masked_fill(mask == 0, float('-inf'))

        attn_weights = torch.softmax(scores, dim=-1)
        attended = torch.matmul(attn_weights, v)  # [B, H, N, D_head]

        # Merge heads
        attended = attended.transpose(1, 2).reshape(B * N, self.embed_dim)

        # Output projection per agent
        out = self.out_proj(attended, batch_indices)
        return out.view(B, N, self.embed_dim)
    
    @torch.no_grad()
    def clone(self, clone_mask: torch.Tensor, clone_indices: torch.Tensor):
        for module in self.children():
            if isinstance(module, BatchedLinear):
                module.clone(clone_mask, clone_indices)

    @torch.no_grad()
    def mutate(self, mutation_mask: torch.Tensor, noise_std: float = 0.01):
        for module in self.children():
            if isinstance(module, BatchedLinear):
                module.mutate(mutation_mask, noise_std=noise_std)



if __name__ == "__main__":
    torch.manual_seed(42)

    B = 4  # batch size / number of agents
    N = 3  # number of visible neighbors
    D = 16  # embedding dimension
    H = 2  # number of heads

    layer = BatchedAttention(batch_size=B, embed_dim=D, num_heads=H)

    # Input: random tensor for B agents each observing N neighbors of D features
    x = torch.randn(B, N, D)

    # Optional mask: 1 = valid, 0 = padding
    mask = torch.ones(B, N)

    print("🔍 Forward pass before mutation:")
    out = layer(x, mask)
    print("Output shape:", out.shape)

    # Print sum of weights for verification
    for name, mod in layer.named_children():
        if isinstance(mod, BatchedLinear):
            print(f"{name}.weight sum:", mod.weight.sum(dim=(1, 2)))

    # Simulate a clone: clone agent 2 from agent 0
    clone_mask = torch.tensor([0, 0, 1, 0])
    clone_indices = torch.tensor([0, 0, 0, 0])
    layer.clone(clone_mask, clone_indices)

    print("\n🔁 After clone:")
    for name, mod in layer.named_children():
        if isinstance(mod, BatchedLinear):
            print(f"{name}.weight sum:", mod.weight.sum(dim=(1, 2)))

    # Mutate the same agent
    layer.mutate(clone_mask)

    print("\n💥 After mutate:")
    for name, mod in layer.named_children():
        if isinstance(mod, BatchedLinear):
            print(f"{name}.weight sum:", mod.weight.sum(dim=(1, 2)))

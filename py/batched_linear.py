import torch
import torch.nn as nn
import torch.nn.functional as F
import time


class BatchedLinear(nn.Module):
    def __init__(self, batch_size, in_features, out_features, bias=True):
        super().__init__()
        self.batch_size = batch_size
        self.weight = nn.Parameter(torch.randn(batch_size, out_features, in_features))
        self.bias = nn.Parameter(torch.randn(batch_size, out_features)) if bias else None

    def forward(self, x):
        out = torch.einsum('bi,bij->bj', x, self.weight.transpose(1, 2))
        if self.bias is not None:
            out = out + self.bias
        return out

    @torch.no_grad()
    def clone(self, clone_mask: torch.Tensor, clone_indices: torch.Tensor):
        """
        clone_mask: Bool or int tensor of shape (N,)
        clone_indices: Int tensor of shape (N,)
        """
        assert clone_mask.shape == (self.batch_size,)
        assert clone_indices.shape == (self.batch_size,)
        assert clone_indices.dtype == torch.int32 or clone_indices.dtype == torch.int64
        assert clone_mask.dtype in [torch.bool, torch.uint8, torch.int32, torch.int64]

        clone_mask = clone_mask.bool()
        clone_from = torch.arange(self.batch_size, device=self.weight.device)[clone_mask]
        clone_to = clone_indices[clone_mask]

        # Sanity check: all clone_from must be in valid range
        if not torch.all((0 <= clone_from) & (clone_from < self.batch_size)):
            raise ValueError("Invalid indices in clone_indices")

        self.weight[clone_from] = self.weight[clone_to]
        if self.bias is not None:
            self.bias[clone_from] = self.bias[clone_to]

    @torch.no_grad()
    def mutate(self, mutation_mask: torch.Tensor, noise_std: float = 0.01):
        # wherever the mask is True, add random noise from -mutation_amplitude to +mutation_amplitude
        assert mutation_mask.shape == (self.batch_size,)
        mutation_mask = mutation_mask.bool()
        noise_shape = (mutation_mask.sum(), *self.weight.shape[1:])
        # NOTE: purposefully a normal distribution because it's more likely to produce small perturbations
        # but still allows for more rare larger mutations.
        noise = (torch.randn(noise_shape, device=self.weight.device) * 2 - 1) * noise_std
        self.weight[mutation_mask] += noise

if __name__ == "__main__":
    # Benchmarking
    B, D_in, H, D_out = 64, 6, 64, 18
    x = torch.randn(B, D_in)

    model = torch.nn.Sequential(
        BatchedLinear(B, D_in, H),
        torch.nn.ReLU(),
        BatchedLinear(B, H, D_out),
    )

    # Warm-up
    for _ in range(10):
        model(x)

    # Timing
    start = time.time()
    for _ in range(1000):
        model(x)
    end = time.time()

    print(f"Average time per forward pass: {(end - start) / 1000 * 1000:.3f} ms")

    # Example usage of clone
    layer = BatchedLinear(4, 6, 18)
    print("Before clone:\n", layer.weight.sum(dim=(1, 2)))

    clone_mask = torch.tensor([0, 0, 1, 0])
    clone_indices = torch.tensor([0, 0, 3, 0])
    layer.clone(clone_mask, clone_indices)

    print("After clone:\n", layer.weight.sum(dim=(1, 2)))

    # mutate with the same mask
    layer.mutate(clone_mask)
    print("After mutate:\n", layer.weight.sum(dim=(1, 2)))
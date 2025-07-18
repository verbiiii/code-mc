import torch
import torch.nn as nn
import torch.nn.functional as F
import time
from abc import ABC


def _extract_from_and_to(clone_mask, clone_indices, batch_size):
    """
    Extracts the indices to clone from and to based on the clone mask and indices.
    """
    assert clone_mask.shape == (batch_size,)
    assert clone_indices.shape == (batch_size,)
    assert clone_indices.dtype in [torch.int32, torch.int64]
    assert clone_mask.dtype in [torch.bool, torch.uint8, torch.int32, torch.int64]

    clone_mask = clone_mask.bool()
    clone_from = torch.arange(batch_size, device=clone_indices.device)[clone_mask]
    clone_to = clone_indices[clone_mask]

    # Sanity check: all clone_from must be in valid range
    if not torch.all((0 <= clone_from) & (clone_from < clone_mask.shape[0])):
        raise ValueError("Invalid indices in clone_indices")

    return clone_from, clone_to


class BatchedNNModule(nn.Module, ABC):
    def __init__(self, batch_size):
        super(BatchedNNModule, self).__init__()
        self.batch_size = batch_size

    @torch.no_grad()
    def clone(self, clone_mask: torch.Tensor, clone_indices: torch.Tensor):
        """Clone parameters based on the provided mask and indices."""
        raise NotImplementedError("Subclasses should implement this method")

    @torch.no_grad()
    def blend(self, mask: torch.Tensor, indices: torch.Tensor):
        """Blend parameters based on the provided mask and indices."""
        raise NotImplementedError("Subclasses should implement this method")

    @torch.no_grad()
    def mutate(self, mutation_mask: torch.Tensor, noise_std: float = 0.01):
        """Mutate parameters based on the provided mask."""
        raise NotImplementedError("Subclasses should implement this method")
    
    @torch.no_grad()
    def calculate_distances(self, partner_indices: torch.Tensor) -> torch.Tensor:
        """Calculate Euclidean distances between agent parameters and their partners."""
        raise NotImplementedError("Subclasses should implement this method")


class BatchedLinear(BatchedNNModule):
    def __init__(self, batch_size, in_features, out_features, bias=True):
        super().__init__(batch_size)

        self.in_features = in_features
        self.out_features = out_features

        self.weight = nn.Parameter(torch.randn(batch_size, out_features, in_features))
        self.bias = nn.Parameter(torch.randn(batch_size, out_features)) if bias else None

    def forward(self, x):
        if x.dim() == 2:  # [B, D]
            assert x.shape == (self.batch_size, self.in_features), \
                f"Expected input shape ({self.batch_size}, {self.in_features}), got {x.shape}"
            out = torch.einsum('bi,bij->bj', x, self.weight.transpose(1, 2))  # [B, O]
            if self.bias is not None:
                assert self.bias.shape == (self.batch_size, self.out_features)
                out = out + self.bias
            assert out.shape == (self.batch_size, self.out_features), \
                f"Expected output shape ({self.batch_size}, {self.out_features}), got {out.shape}"

        elif x.dim() == 3:  # [B, N, D]
            B, N, D = x.shape
            assert B == self.batch_size, f"Batch size mismatch: {B} != {self.batch_size}"
            assert D == self.in_features, f"in_features mismatch: {D} != {self.in_features}"
            # x: [B, N, D], weight: [B, O, D] → want: [B, N, O]
            out = torch.einsum('bnd,bod->bno', x, self.weight)
            if self.bias is not None:
                assert self.bias.shape == (self.batch_size, self.out_features)
                out = out + self.bias.unsqueeze(1)  # [B, 1, O]
            assert out.shape == (self.batch_size, N, self.out_features), \
                f"Expected output shape ({self.batch_size}, {N}, {self.out_features}), got {out.shape}"

        else:
            raise ValueError(f"Unsupported input shape {x.shape}")
        
        return out

    @torch.no_grad()
    def clone(self, clone_mask: torch.Tensor, clone_indices: torch.Tensor):
        """
        clone_mask: Bool or int tensor of shape (N,)
        clone_indices: Int tensor of shape (N,)
        """
        
        clone_from, clone_to = _extract_from_and_to(clone_mask, clone_indices, self.batch_size)
        self.weight[clone_from] = self.weight[clone_to]
        if self.bias is not None:
            self.bias[clone_from] = self.bias[clone_to]

    @torch.no_grad()
    def blend(self, mask: torch.Tensor, indices: torch.Tensor):
        """Combine the parameters of the two sets of weights and biases using the provided mask and indices.
        """

        blend_from, blend_to = _extract_from_and_to(mask, indices, self.batch_size)

        # calculate the average of the weights and biases between the two sets
        # TODO: maybe use a weighted average based on the performance of the two sets
        self.weight[blend_from] = (self.weight[blend_from] + self.weight[blend_to]) / 2
        if self.bias is not None:
            self.bias[blend_from] = (self.bias[blend_from] + self.bias[blend_to]) / 2

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

    @torch.no_grad()
    def calculate_distances(self, partner_indices: torch.Tensor) -> torch.Tensor:
        distances = torch.zeros(self.batch_size, device=self.weight.device)

        # Calculate distances for weights
        weight_diffs = self.weight - self.weight[partner_indices]
        weight_distances = torch.norm(weight_diffs.view(self.batch_size, -1), dim=1)
        distances += weight_distances
        
        # Calculate distances for biases if they exist
        if self.bias is not None:
            bias_diffs = self.bias - self.bias[partner_indices]
            distances += torch.norm(bias_diffs, dim=1)

        return distances

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
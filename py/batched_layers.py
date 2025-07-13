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

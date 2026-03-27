import argparse
from pathlib import Path
from typing import Dict, List, Tuple

import torch

from rl_operator import RLOperators


def _infer_num_models(state_dict: Dict[str, torch.Tensor]) -> int:
    for tensor in state_dict.values():
        if torch.is_tensor(tensor) and tensor.dim() > 0:
            return int(tensor.shape[0])
    raise ValueError("Could not infer model count from state_dict.")


def _per_model_param_counts(state_dict: Dict[str, torch.Tensor], num_models: int) -> List[int]:
    counts = [0 for _ in range(num_models)]
    for tensor in state_dict.values():
        if not torch.is_tensor(tensor):
            continue
        if tensor.dim() > 0 and int(tensor.shape[0]) == num_models:
            per_model = tensor[0].numel()
            for i in range(num_models):
                counts[i] += per_model
        else:
            shared = tensor.numel()
            for i in range(num_models):
                counts[i] += shared
    return counts


def _per_model_stats(state_dict: Dict[str, torch.Tensor], num_models: int) -> List[Tuple[float, float, float]]:
    sums = [0.0 for _ in range(num_models)]
    counts = [0 for _ in range(num_models)]
    mins = [float("inf") for _ in range(num_models)]
    maxs = [float("-inf") for _ in range(num_models)]

    for tensor in state_dict.values():
        if not torch.is_tensor(tensor):
            continue

        t = tensor.detach().float().cpu()
        if t.dim() > 0 and int(t.shape[0]) == num_models:
            flat = t.reshape(num_models, -1)
            s = flat.sum(dim=1)
            mi = flat.min(dim=1).values
            ma = flat.max(dim=1).values
            n = flat.shape[1]
            for i in range(num_models):
                sums[i] += float(s[i].item())
                counts[i] += int(n)
                mins[i] = min(mins[i], float(mi[i].item()))
                maxs[i] = max(maxs[i], float(ma[i].item()))
        else:
            shared_flat = t.reshape(-1)
            shared_sum = float(shared_flat.sum().item())
            shared_min = float(shared_flat.min().item())
            shared_max = float(shared_flat.max().item())
            shared_n = int(shared_flat.numel())
            for i in range(num_models):
                sums[i] += shared_sum
                counts[i] += shared_n
                mins[i] = min(mins[i], shared_min)
                maxs[i] = max(maxs[i], shared_max)

    out: List[Tuple[float, float, float]] = []
    for i in range(num_models):
        mean = sums[i] / max(counts[i], 1)
        out.append((mean, mins[i], maxs[i]))
    return out


def analyze_checkpoint(path: Path) -> None:
    if not path.exists():
        raise FileNotFoundError(f"Checkpoint path does not exist: {path}")
    if not path.is_file():
        raise ValueError(f"Expected a checkpoint file path, got directory: {path}")
    if path.suffix.lower() != ".pth":
        raise ValueError(f"Expected a .pth checkpoint file, got: {path}")

    state_dict = torch.load(path, map_location="cpu")
    if not isinstance(state_dict, dict):
        raise ValueError(f"Checkpoint is not a state_dict dict: {path}")

    num_models = _infer_num_models(state_dict)

    # Load the operator to verify compatibility and keep analysis grounded to model code.
    operators = RLOperators(num_agents=num_models, device="cpu")
    operators.load_state_dict(state_dict)

    counts = _per_model_param_counts(operators.state_dict(), num_models)
    stats = _per_model_stats(operators.state_dict(), num_models)

    print(f"checkpoint: {path}")
    print(f"models: {num_models}")
    print("per-model summary:")
    for i in range(num_models):
        mean, min_val, max_val = stats[i]
        print(
            f"  agent {i:03d}: params={counts[i]:,} "
            f"mean={mean:.6f} min={min_val:.6f} max={max_val:.6f}"
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyze one RLOperators checkpoint (.pth state_dict file)."
    )
    parser.add_argument(
        "path",
        type=str,
        help="Path to exactly one .pth checkpoint file.",
    )
    args = parser.parse_args()
    analyze_checkpoint(Path(args.path))


if __name__ == "__main__":
    main()

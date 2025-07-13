#!/usr/bin/env python3
"""
Configuration and quick start for FMC Minecraft training.
"""

from binary_transport import initialize_transport

# FMC Configuration
FMC_CONFIG = {
    "algorithm": "fmc",      # "fmc" or "reinforce"
    "device": "cpu",         # "cpu" or "cuda"
    "num_agents": 64,        # Population size for FMC
}

def start_fmc_training():
    """Start FMC evolution training."""
    print("🧬 Starting FMC Evolution Training for Minecraft RL")
    initialize_transport(
        device=FMC_CONFIG["device"],
        algorithm=FMC_CONFIG["algorithm"],
        num_agents=FMC_CONFIG["num_agents"]
    )
    print("✅ FMC training ready! Connect from Java...")

def start_reinforce_training():
    """Start traditional REINFORCE training for comparison."""
    print("🎯 Starting PyTorch REINFORCE Training for Minecraft RL")
    initialize_transport(
        device=FMC_CONFIG["device"],
        algorithm="reinforce",
        num_agents=FMC_CONFIG["num_agents"]
    )
    print("✅ REINFORCE training ready! Connect from Java...")

if __name__ == "__main__":
    import sys
    
    if len(sys.argv) > 1 and sys.argv[1].lower() == "reinforce":
        start_reinforce_training()
    else:
        start_fmc_training()

import mc
import xos

agent_positions = mc.agents.positions
num_agents = int(agent_positions.shape[0]) if len(agent_positions.shape) > 0 else 0

if num_agents == 0:
    print("no agents found")
else:
    # Batch player position to (N,3) tensor, index-aligned to mc.agents
    p = mc.player.position
    player_positions = (
        xos.tensor([p[0], p[1], p[2]], (1, 3))
        .repeat(num_agents, axis=0)
    )

    delta = player_positions - agent_positions
    dx = delta[:, 0]
    dy = delta[:, 1]
    dz = delta[:, 2]

    # Vectorized look-at in degrees (Minecraft yaw/pitch convention)
    yaws = xos.math.degrees(xos.math.atan2(dz, dx)) - 90.0
    horizontal = xos.math.sqrt(dx * dx + dz * dz)
    pitches = -xos.math.degrees(xos.math.atan2(dy, horizontal))

    rotations_tensor = xos.stack([yaws, pitches], axis=1)
    mc.agents.rotations = rotations_tensor

    print("updated", num_agents, "agent rotations to look at player")

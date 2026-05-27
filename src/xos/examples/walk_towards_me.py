import mc
import xos

while True:
    agent_positions = mc.agents.positions
    num_agents = int(agent_positions.shape[0]) if len(agent_positions.shape) > 0 else 0
    if num_agents <= 0:
        xos.sleep(0.1)
        continue

    player_position = mc.player.position
    player_positions = xos.tensor(
        [player_position[0], player_position[1], player_position[2]], (1, 3)
    ).repeat(num_agents, axis=0)

    delta = player_positions - agent_positions
    dx = delta[:, 0]
    dy = delta[:, 1]
    dz = delta[:, 2]

    target_yaws = xos.math.degrees(xos.math.atan2(dz, dx)) - 90.0
    horizontal = xos.math.sqrt(dx * dx + dz * dz)
    target_pitches = -xos.math.degrees(xos.math.atan2(dy, horizontal))
    target_rotations = xos.stack([target_pitches, target_yaws], axis=1)

    current_rotations = mc.agents.rotations
    look_deltas = target_rotations - current_rotations
    mc.agents.look(look_deltas)
    mc.agents.move((True, False, False, False))

    xos.sleep(0.1)

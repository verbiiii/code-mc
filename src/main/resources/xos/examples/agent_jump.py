import mc

for agent in mc.agents:
    v = agent.velocity
    agent.velocity = (v[0], 3.0, v[2])

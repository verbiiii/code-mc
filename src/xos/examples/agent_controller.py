import mc

print(mc.player.position)

for agent in mc.agents:
    mc.chat(agent.position)

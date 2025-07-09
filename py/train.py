import numpy as np


class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0

    def update_state(self, volume_array: np.ndarray, my_position: tuple, enemy_position: tuple):
        self.volume_array = volume_array
        self.my_position = my_position
        self.enemy_position = enemy_position

    def update(self, info: dict):
        self.python_ticks += 1
        java_tick = info.get("tick", 0)

        is_first_tick = info["is_first_tick"]
        is_last_tick = info["is_last_tick"]

        if is_first_tick:
            print("🚀 Starting new train session...")

        rl_ids = info.get("rl_operator_ids", [])
        all_ops = info.get("all_operators", {})

        for oid in rl_ids:
            data = all_ops.get(oid, {})
            dmg_dealt = data.get("damage_dealt_last_tick", 0.0)
            dmg_taken = data.get("damage_taken_last_tick", 0.0)
            reward = dmg_dealt - dmg_taken
            print(f"🎯 [py_tick={self.python_ticks}, jv_tick={java_tick}] Operator {oid[:4]}.. reward: {reward:.2f}")

            if (data["deaths_last_tick"] > 0):
                print(f"💀 Operator {oid[:4]}.. died last tick")
                reward -= 100.0

            if (data["kills_last_tick"] > 0):
                print(f"🏆 Operator {oid[:4]}.. killed last tick")
                reward += 100.0
            
        if is_last_tick:
            print("🏁 Ending train session...")

    def sample_action(self):
        # randomly sample our actions (super temporary)

        # generate random value for should walk
        should_walk = np.random.rand() < 0.5

        # now, choose a random degree between 0 and 360
        degree = np.random.randint(0, 360) if should_walk else None

        should_shoot = np.random.rand() < 0.5

        return degree, should_shoot
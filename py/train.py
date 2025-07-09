import numpy as np


class TrainState1v1:

    def __init__(self):
        pass

    def update_state(self, volume_array: np.ndarray, my_position: tuple, enemy_position: tuple):
        self.volume_array = volume_array
        self.my_position = my_position
        self.enemy_position = enemy_position

    def update(self, info: dict):
        rl_ids = info.get("rl_operator_ids", [])
        all_ops = info.get("all_operators", {})

        for oid in rl_ids:
            data = all_ops.get(oid, {})
            dmg_dealt = data.get("damage_dealt_last_tick", 0.0)
            dmg_taken = data.get("damage_taken_last_tick", 0.0)
            reward = dmg_dealt - dmg_taken

            print(f"🎯 Operator {oid[:4]}.. reward: {reward:.2f}")

    def sample_action(self):
        # randomly sample our actions (super temporary)

        # generate random value for should walk
        should_walk = np.random.rand() < 0.5

        # now, choose a random degree between 0 and 360
        degree = np.random.randint(0, 360) if should_walk else None

        should_shoot = np.random.rand() < 0.5

        return degree, should_shoot
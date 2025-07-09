import numpy as np


class TrainState1v1:

    def __init__(self):
        pass

    def update_state(self, volume_array: np.ndarray, my_position: tuple, enemy_position: tuple):
        self.volume_array = volume_array
        self.my_position = my_position
        self.enemy_position = enemy_position

    def sample_action(self):
        # randomly sample our actions (super temporary)

        # generate random value for should walk
        should_walk = np.random.rand() < 0.5

        # now, choose a random degree between 0 and 360
        degree = np.random.randint(0, 360) if should_walk else None

        should_shoot = np.random.rand() < 0.5

        return degree, should_shoot
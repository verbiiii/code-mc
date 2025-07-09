import numpy as np

import torch


class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0
        self.tick_x = None

        # model is a feed forward from 4 features to 6 outputs
        self.model = torch.nn.Sequential(
            torch.nn.Linear(4, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 32),
            torch.nn.Tanh(),
            torch.nn.Linear(32, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 6),
            # NOTE: we should definitely only be using tanh, at least on the output layer.
            torch.nn.Tanh(),
        )

    def update(self, info: dict):
        self.python_ticks += 1
        java_tick = info.get("tick", 0)

        is_first_tick = info.get("is_first_tick", False)
        is_last_tick = info.get("is_last_tick", False)
        round_num = info.get("round", -1)
        event = info.get("event", None)

        if event == "start_session":
            print(f"🎬 Session started with {info.get('rounds', '?')} rounds!")
        elif event == "end_session":
            print(f"✅ Session complete after {info.get('rounds', '?')} rounds!")
        elif event == "start_round":
            print(f"🌀 Round {round_num + 1} starting!")
        elif event == "end_round":
            print(f"⛔ Round {round_num + 1} complete!")

        rl_ids = info.get("rl_operator_ids", [])
        all_ops = info.get("all_operators", {})

        if len(rl_ids) > 1:
            raise NotImplementedError("Currently the RL agent can only handle 1 RLOperator at a time.")

        for oid in rl_ids:
            data = all_ops.get(oid, {})
            dmg_dealt = data.get("damage_dealt_last_tick", 0.0)
            dmg_taken = data.get("damage_taken_last_tick", 0.0)
            reward = dmg_dealt - dmg_taken
            short_id = oid[:4]

            print(f"🎯 Tick {self.python_ticks}/{java_tick} | 🧠 {short_id} | 📈 Reward: {reward:.2f}")

            if data.get("deaths_last_tick", 0) > 0:
                print(f"💀 {short_id} died!")
                reward -= 100.0

            if data.get("kills_last_tick", 0) > 0:
                print(f"🏆 {short_id} got a kill!")
                reward += 100.0

        if is_first_tick and not event:
            print("🚀 Java round started (but no event tag?)")
        if is_last_tick and not event:
            print("🏁 Java round ending (but no event tag?)")

        if len(rl_ids) > 0:
            self.our_data = all_ops[rl_ids[0]]
            self.our_x = self.our_data.get("x")
            self.our_y = self.our_data.get("y")
            self.our_z = self.our_data.get("z")
            self.our_health = self.our_data["health"]

            # create our feature vector
            self.tick_x = torch.tensor(np.array([self.our_x, self.our_y, self.our_z, self.our_health], dtype=np.float32), dtype=torch.float32).unsqueeze(0) # faux batch dim

    def sample_action(self):
        if self.tick_x is None:
            raise ValueError("Cannot sample action before updating the train state with game info.")

        # randomly sample our actions (super temporary)

        # # generate random value for should walk
        # should_walk = np.random.rand() < 0.5

        # # now, choose a random degree between 0 and 360
        # degree = np.random.randint(0, 360) if should_walk else None

        # should_shoot = np.random.rand() < 0.5

        # return degree, should_shoot

        # let's do a stochastic policy
        # so let's get an output feature vector of [should_move, should_shoot, mu0, sigma0, mu1, sigma1]
        # the reason for this is 

        # let's use float32s and then apply a tanh
        y = self.model(self.tick_x)

        print(y)

        # NOTE: we should definitely only be using tanh.

        # now, let's get the true/false values and the x and y vector component distributions
        should_move = y[0, 0] > 0
        should_shoot = y[0, 1] > 0

        mu0 = y[0, 2]
        sigma0 = y[0, 3]
        mu1 = y[0, 4]
        sigma1 = y[0, 5]

        # convert sigmas from -1 1 range to 0 to 1 range
        sigma0 = (sigma0 + 1) / 2
        sigma1 = (sigma1 + 1) / 2

        # now, we can use torch.distributions.Normal to sample from the distributions
        x_dist = torch.distributions.Normal(mu0, sigma0)
        y_dist = torch.distributions.Normal(mu1, sigma1)

        # now we need to sample points and then convert them into 0-360 degrees
        x_sample = x_dist.sample()
        y_sample = y_dist.sample()

        # the x and y sample represent a vector we need to convert into a degree clockwise from (0, 1)
        angle = torch.atan2(y_sample, x_sample)
        angle = torch.atan2(y_sample, x_sample)
        angle = torch.rad2deg(angle)  # convert to degrees
        angle = (angle + 360) % 360   # wrap to [0, 360)
        angle = angle.item()

        # now, we return the action. angle becomes none if we chose not to move.
        if not should_move:
            angle = None

        print(angle, should_move, should_shoot)

        return angle, should_shoot
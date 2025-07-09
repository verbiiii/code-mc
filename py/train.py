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

        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=1e-1)
        self.log_probs = []
        self.rewards = []

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
            return self.apply_reinforce()

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

            self.rewards.append(reward)

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

        # run forward pass
        y = self.model(self.tick_x)  # shape: [1, 6]

        # unpack into means and log stds (more stable if you later optimize)
        mu = y[0, ::2]     # [μ₁, μ₂, μ₃]
        sigma_raw = y[0, 1::2]  # [σ₁, σ₂, σ₃] ∈ [-1, 1]

        # map sigma from [-1, 1] → [0.01, 1.0] (avoid exact 0 std)
        sigma = 0.5 * (sigma_raw + 1.0) * 0.99 + 0.01

        # build a multivariate distribution
        dist = torch.distributions.Normal(loc=mu, scale=sigma)

        # sample all at once
        sample = dist.rsample()  # enables backprop
        log_prob = dist.log_prob(sample)  # shape: [3]
        self.log_probs.append(log_prob.sum())  # store total log prob for this step

        move_val, shoot_val, angle_val = sample.tolist()

        # binarize movement/shooting decisions
        should_move = move_val > 0
        should_shoot = shoot_val > 0

        # wrap angle to [0, 360)
        angle = torch.rad2deg(torch.tensor(angle_val))
        angle = (angle + 360) % 360
        angle = angle.item()

        if not should_move:
            angle = None

        return angle, should_shoot

    def apply_reinforce(self):
        if not self.log_probs or not self.rewards:
            return

        # convert to tensors
        rewards = torch.tensor(self.rewards, dtype=torch.float32)
        log_probs = torch.stack(self.log_probs)  # shape: [T]

        # optional: normalize rewards
        rewards = (rewards - rewards.mean()) / (rewards.std() + 1e-5)

        # REINFORCE loss: -Σ log_prob × reward
        loss = -(log_probs * rewards).mean()

        print(f"🧮 REINFORCE loss = {loss.item():.4f}")

        self.optimizer.zero_grad()
        loss.backward()
        self.optimizer.step()

        # clear for next round
        self.log_probs.clear()
        self.rewards.clear()

        print("✅ Model updated using REINFORCE")

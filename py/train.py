import numpy as np

import torch


class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0
        self.tick_x = None

        self.model = torch.nn.Sequential(
            torch.nn.Linear(4, 8),
            torch.nn.Tanh(),
            torch.nn.Linear(8, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 32),
            torch.nn.Tanh(),
            torch.nn.Linear(32, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 18),  # 8 x-bins, 8 y-bins, 2 binary
        )

        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=0.05)

        self.log_probs = []
        self.rewards = []
        self.all_rewards = []  # <-- store all-time rewards


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
            reward = dmg_dealt - (dmg_taken * 10)
            short_id = oid[:4]

            print(f"🎯 Tick {self.python_ticks}/{java_tick} | 🧠 {short_id} | 📈 Reward: {reward:.2f}")

            if data.get("deaths_last_tick", 0) > 0:
                print(f"💀 {short_id} died!")
                reward -= 1000.0

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

        y = self.model(self.tick_x).squeeze(0)  # shape: [18]

        # Split outputs
        logits_x = y[:8]
        logits_y = y[8:16]
        logit_walk = y[16]
        logit_shoot = y[17]

        # Build distributions
        dist_x = torch.distributions.Categorical(logits=logits_x)
        dist_y = torch.distributions.Categorical(logits=logits_y)
        dist_walk = torch.distributions.Bernoulli(logits=logit_walk)
        dist_shoot = torch.distributions.Bernoulli(logits=logit_shoot)

        # Sample
        x_bin = dist_x.sample()
        y_bin = dist_y.sample()
        walk = dist_walk.sample().item() > 0.5
        shoot = dist_shoot.sample().item() > 0.5

        # Log probs for REINFORCE
        log_prob = (
            dist_x.log_prob(x_bin) +
            dist_y.log_prob(y_bin) +
            dist_walk.log_prob(torch.tensor(float(walk))) +
            dist_shoot.log_prob(torch.tensor(float(shoot)))
        )
        self.log_probs.append(log_prob)

        # Map bins to angle
        if walk:
            angle = (x_bin.item() / 8.0) * 360.0  # map to [0, 360)
        else:
            angle = None

        # Print diagnostic
        entropy = (
            dist_x.entropy() + dist_y.entropy() +
            dist_walk.entropy() + dist_shoot.entropy()
        ).item()
        print(f"🎲 Action bins: x={x_bin.item()}, y={y_bin.item()}, walk={walk}, shoot={shoot}")
        print(f"🔍 Forward statistics: entropy={entropy:.4f}")

        return angle, shoot


    def apply_reinforce(self):
        if not self.log_probs or not self.rewards:
            return

        # convert to tensors
        rewards = torch.tensor(self.rewards, dtype=torch.float32)
        log_probs = torch.stack(self.log_probs)

        # --- ⬇️ Add to global reward history
        self.all_rewards.extend(self.rewards)
        # Optional: limit memory usage
        MAX_REWARD_HISTORY = 10_000
        if len(self.all_rewards) > MAX_REWARD_HISTORY:
            self.all_rewards = self.all_rewards[-MAX_REWARD_HISTORY:]

        all_rewards_tensor = torch.tensor(self.all_rewards, dtype=torch.float32)
        global_mean = all_rewards_tensor.mean()
        global_std = all_rewards_tensor.std() + 1e-5

        # normalize with global stats
        normalized_rewards = (rewards - global_mean) / global_std

        # REINFORCE loss
        loss = -(log_probs * normalized_rewards).mean()

        print(f"🧮 REINFORCE loss = {loss.item():.4f} (normalized over {len(self.all_rewards)} total rewards)")

        self.optimizer.zero_grad()
        loss.backward()
        self.optimizer.step()

        self.log_probs.clear()
        self.rewards.clear()

        print("✅ Model updated using REINFORCE")

import numpy as np

import torch


class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0
        self.tick_x = None

        self.model = torch.nn.Sequential(
            torch.nn.Linear(5, 8),
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
        self.all_cumulative_rewards = []


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
        reward = 0.0

        for oid in rl_ids:
            data = all_ops.get(oid, {})
            dmg_dealt = data.get("damage_dealt_last_tick", 0.0)
            dmg_taken = data.get("damage_taken_last_tick", 0.0)
            reward = dmg_dealt - (dmg_taken * 10)
            short_id = oid[:4]

            print(f"🎯 Tick {self.python_ticks}/{java_tick} | 🧠 {short_id} | 📈 Reward: {reward:.2f}")

            died_last_tick = data.get("deaths_last_tick", 0) > 0
            killed_last_tick = data.get("kills_last_tick", 0) > 0

            if died_last_tick:
                if not killed_last_tick:
                    print(f"💔 {short_id} lost (died 💀)")
                    reward -= 1000.0

            if killed_last_tick:
                if not died_last_tick:
                    print(f"🏆 {short_id} won!")
                    reward += 1000.0

            # print if it was a draw
            if is_last_tick:
                if died_last_tick and killed_last_tick:
                    reward -= 250.0
                    print(f"🤝 {short_id} drew! Because both agents died!")
                elif not died_last_tick and not killed_last_tick:
                    reward -= 500.0
                    print(f"🤷 {short_id} round ended in a tie! (no deaths or kills)")

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
            self.tick_x = torch.tensor(np.array([self.our_x, self.our_y, self.our_z, self.our_health, reward], dtype=np.float32), dtype=torch.float32).unsqueeze(0) # faux batch dim

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
        # print(f"🎲 Action bins: x={x_bin.item()}, y={y_bin.item()}, walk={walk}, shoot={shoot}")
        print(f"🔍 Forward statistics: entropy={entropy:.4f}")

        return angle, shoot

    def apply_reinforce(self):
        if not self.log_probs or not self.rewards:
            return

        cumulative_reward = sum(self.rewards)
        self.all_cumulative_rewards.append(cumulative_reward)

        # Trim history
        MAX_HISTORY = 10_000
        if len(self.all_cumulative_rewards) > MAX_HISTORY:
            self.all_cumulative_rewards = self.all_cumulative_rewards[-MAX_HISTORY:]

        # Safe normalization
        if len(self.all_cumulative_rewards) < 2:
            normalized_cumulative = torch.tensor(1.0)  # no normalization on 1st round
            print(f"⚠️ Not enough reward history — skipping normalization.")
        else:
            rewards_tensor = torch.tensor(self.all_cumulative_rewards, dtype=torch.float32)
            mean = rewards_tensor.mean()
            std = rewards_tensor.std()
            if std.item() == 0 or torch.isnan(std):
                normalized_cumulative = torch.tensor(0.0)
                print(f"⚠️ Std=0 or NaN — setting normalized_cumulative to 0.0")
            else:
                normalized_cumulative = (cumulative_reward - mean) / std
                # print(f"🧪 Raw REW: {cumulative_reward:.2f} | µ={mean:.2f} σ={std:.2f} → norm={normalized_cumulative:.4f}")

        print("Normalized cumulative reward:", normalized_cumulative.item())

        log_probs_tensor = torch.stack(self.log_probs)
        loss = -(log_probs_tensor * normalized_cumulative).mean()

        if torch.isnan(loss):
            print("🚨 Loss is NaN — skipping optimizer step.")
        else:
            self.optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(self.model.parameters(), 10.0)
            self.optimizer.step()
            print(f"✅ Model updated using normalized episode reward")

        self.log_probs.clear()
        self.rewards.clear()


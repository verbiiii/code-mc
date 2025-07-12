import numpy as np
import torch

class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0
        self.model = torch.nn.Sequential(
            torch.nn.Linear(6, 8),  # [my_x, my_y, my_z, opp_x, opp_y, opp_z]
            torch.nn.Tanh(),
            torch.nn.Linear(8, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 32),
            torch.nn.Tanh(),
            torch.nn.Linear(32, 16),
            torch.nn.Tanh(),
            torch.nn.Linear(16, 18),  # 8 x-bins, 8 y-bins, 2 binary
        )
        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=0.001)

        # Per-agent memory
        self.agent_data = {}  # uuid -> dict with tick_x, log_probs, rewards, etc.
        self.all_cumulative_rewards = []

    def update(self, info: dict):
        self.python_ticks += 1
        java_tick = info.get("tick", 0)

        event = info.get("event")
        if event == "start_session":
            print(f"🎬 Session started with {info.get('rounds', '?')} rounds!")
        elif event == "end_session":
            print(f"✅ Session complete.")
        elif event == "start_round":
            print(f"🌀 Round {info.get('round', '?')+1} starting!")
        elif event == "end_round":
            print(f"⛔ Round {info.get('round', '?')+1} complete!")
            self.apply_reinforce()
            return

        all_ops = info.get("all_operators", {})
        rl_ids = info.get("rl_operator_ids", [])

        # We'll assume 1v1 per agent for now
        for i, my_id in enumerate(rl_ids):
            my_data = all_ops.get(my_id, {})
            other_ids = [oid for oid in all_ops.keys() if oid != my_id]
            opp_data = all_ops.get(other_ids[0], {}) if other_ids else {}

            # Feature vector = [my x, y, z, opp x, y, z]
            fx = np.array([
                my_data.get("x", 0.0),
                my_data.get("y", 0.0),
                my_data.get("z", 0.0),
                opp_data.get("x", 0.0),
                opp_data.get("y", 0.0),
                opp_data.get("z", 0.0),
            ], dtype=np.float32)
            tick_x = torch.tensor(fx).unsqueeze(0)

            # Reward
            dmg_taken = my_data.get("damage_taken_last_tick", 0.0)
            dmg_given = my_data.get("damage_dealt_last_tick", 0.0)

            reward = dmg_given - dmg_taken * 0.1

            if my_data.get("deaths_last_tick", 0) > 0:
                # reward -= 1000.0
                pass  # self-play, let's just ignore our own deaths
            if my_data.get("kills_last_tick", 0) > 0:
                reward += 100

            # Init per-agent memory if missing
            if my_id not in self.agent_data:
                self.agent_data[my_id] = {
                    "log_probs": [],
                    "rewards": [],
                }

            self.agent_data[my_id]["tick_x"] = tick_x
            self.agent_data[my_id]["rewards"].append(reward)

            # print(f"🎯 Tick {self.python_ticks}/{java_tick} | 🧠 {my_id[:4]} | 📈 Reward: {reward:.2f}")

    def sample_actions(self):
        results = {}
        batch_inputs = []
        agent_ids = []

        # Collect valid inputs
        for agent_id, data in self.agent_data.items():
            if "tick_x" in data:
                batch_inputs.append(data["tick_x"])
                agent_ids.append(agent_id)

        if not batch_inputs:
            return results  # nothing to do

        # Stack into batch
        batch_tensor = torch.cat(batch_inputs, dim=0)  # shape: [B, 6]
        y = self.model(batch_tensor)  # shape: [B, 18]

        for i, agent_id in enumerate(agent_ids):
            out = y[i]
            logits_x = out[:8]
            logits_y = out[8:16]
            logit_walk = out[16]
            logit_shoot = out[17]

            dist_x = torch.distributions.Categorical(logits=logits_x)
            dist_y = torch.distributions.Categorical(logits=logits_y)
            dist_walk = torch.distributions.Bernoulli(logits=logit_walk)
            dist_shoot = torch.distributions.Bernoulli(logits=logit_shoot)

            x_bin = dist_x.sample()
            y_bin = dist_y.sample()
            walk = dist_walk.sample().item() > 0.5
            shoot = dist_shoot.sample().item() > 0.5

            log_prob = (
                dist_x.log_prob(x_bin) +
                dist_y.log_prob(y_bin) +
                dist_walk.log_prob(torch.tensor(float(walk))) +
                dist_shoot.log_prob(torch.tensor(float(shoot)))
            )
            self.agent_data[agent_id]["log_probs"].append(log_prob)

            angle = (x_bin.item() / 8.0) * 360.0 if walk else None
            results[agent_id] = (angle, shoot)

        return results

    def apply_reinforce(self):
        agents = list(self.agent_data.items())
        num_agents = len(agents)

        for i, (agent_id, data) in enumerate(agents):
            if not data["log_probs"] or not data["rewards"]:
                continue

            cumulative_reward = sum(data["rewards"])
            self.all_cumulative_rewards.append(cumulative_reward)

            # Normalize reward
            if len(self.all_cumulative_rewards) < 2:
                norm_reward = torch.tensor(1.0)
            else:
                r = torch.tensor(self.all_cumulative_rewards, dtype=torch.float32)
                std = r.std()
                if std == 0 or torch.isnan(std):
                    norm_reward = torch.tensor(0.0)
                else:
                    norm_reward = (cumulative_reward - r.mean()) / std

            log_probs_tensor = torch.stack(data["log_probs"])
            loss = -(log_probs_tensor * norm_reward).mean()

            if not torch.isnan(loss):
                retain = i < num_agents - 1  # only retain graph if more agents remain
                self.optimizer.zero_grad() if i == 0 else None
                loss.backward(retain_graph=retain)
                if i == num_agents - 1:
                    torch.nn.utils.clip_grad_norm_(self.model.parameters(), 10.0)
                    self.optimizer.step()
                    # print(f"✅ Updated model from agent {agent_id[:4]} episode reward")
            else:
                print(f"🚨 Loss is NaN for agent {agent_id[:4]} — skipping optimizer step.")

            data["log_probs"].clear()
            data["rewards"].clear()


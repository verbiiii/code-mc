import numpy as np


class TrainState1v1:

    def __init__(self):
        self.python_ticks = 0

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

            # create a numpy array with that information
            self.tick_x = np.array([self.our_x, self.our_y, self.our_z, self.our_health], dtype=np.float32)
            # print(self.tick_x)

    def sample_action(self):
        # randomly sample our actions (super temporary)

        # generate random value for should walk
        should_walk = np.random.rand() < 0.5

        # now, choose a random degree between 0 and 360
        degree = np.random.randint(0, 360) if should_walk else None

        should_shoot = np.random.rand() < 0.5

        return degree, should_shoot
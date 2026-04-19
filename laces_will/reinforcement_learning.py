from py4j.java_gateway import JavaGateway, GatewayParameters, CallbackServerParameters
import random
from collections import deque

gateway = None

ACTIONS = [
    [0.7, 0.2, 0.1],  # latency-heavy
    [0.4, 0.4, 0.2],  # balanced
    [0.2, 0.6, 0.2],  # compute-heavy
    [0.2, 0.3, 0.5],  # data-heavy
]

class WeightProvider(object):
    def __init__(self):
        self.epsilon = 0.1
        self.q_values = [0.0 for _ in ACTIONS]
        self.counts = [0 for _ in ACTIONS]

        self.last_action = None
        self.last_context = None

        self.step_count = 0
        self.reward_count = 0
        self.recent_rewards = deque(maxlen=50)

        self.success_count = 0
        self.fail_count = 0

        # 로그 빈도 조절
        self.print_every_action = 50      # action x번마다 출력
        self.print_every_reward = 50      # reward x번마다 출력
        self.print_context = False        # True면 context도 출력

    def _to_java_double_array(self, values):
        arr = gateway.new_array(gateway.jvm.double, len(values))
        for i, v in enumerate(values):
            arr[i] = float(v)
        return arr

    def _format_q_values(self):
        return "[" + ", ".join(f"{q:.4f}" for q in self.q_values) + "]"

    def _format_counts(self):
        return "[" + ", ".join(str(c) for c in self.counts) + "]"

    def _print_status(self, prefix: str):
        avg_recent = (
            sum(self.recent_rewards) / len(self.recent_rewards)
            if self.recent_rewards else 0.0
        )
        print(
            f"{prefix} | steps={self.step_count} rewards={self.reward_count} "
            f"epsilon={self.epsilon:.3f} "
            f"Q={self._format_q_values()} "
            f"counts={self._format_counts()} "
            f"recent_avg_reward={avg_recent:.4f}"
        )

    def getWeights(self, context):
        self.step_count += 1
        context_list = [float(x) for x in context]
        self.last_context = context_list

        # epsilon-greedy
        if random.random() < self.epsilon:
            action_idx = random.randrange(len(ACTIONS))
            decision_type = "explore"
        else:
            best_q = max(self.q_values)
            candidates = [i for i, q in enumerate(self.q_values) if q == best_q]
            action_idx = random.choice(candidates)
            decision_type = "exploit"

        self.last_action = action_idx
        weights = ACTIONS[action_idx]

        if self.step_count % self.print_every_action == 0:
            print(
                f"[ACTION] step={self.step_count} type={decision_type} "
                f"action={action_idx} weights={weights}"
            )
            if self.print_context:
                print(f"         context={context_list}")
            self._print_status("[ACTION STATUS]")

        return self._to_java_double_array(weights)

    def reportReward(self, reward):
        reward = float(reward)
        self.reward_count += 1
        self.recent_rewards.append(reward)

        if reward > 0:
            self.success_count += 1
        else:
            self.fail_count += 1

        if self.last_action is None:
            print(f"[REWARD] reward={reward:.4f} ignored because last_action is None")
            return

        a = self.last_action
        self.counts[a] += 1
        n = self.counts[a]

        old_q = self.q_values[a]
        self.q_values[a] += (reward - self.q_values[a]) / n
        new_q = self.q_values[a]

        if self.reward_count % self.print_every_reward == 0:
            print(f"success={self.success_count} fail={self.fail_count}")
            print(
                f"[REWARD] idx={self.reward_count} action={a} "
                f"reward={reward:.4f} old_q={old_q:.4f} new_q={new_q:.4f}"
            )
            self._print_status("[REWARD STATUS]")

    class Java:
        implements = ["edu.boun.edgecloudsim.applications.laces.WeightProvider"]


def main():
    global gateway
    gateway = JavaGateway(
        gateway_parameters=GatewayParameters(address="127.0.0.1", port=25333),
        callback_server_parameters=CallbackServerParameters()
    )

    bridge = gateway.entry_point
    provider = WeightProvider()
    bridge.registerWeightProvider(provider)

    print("Python WeightProvider registered successfully.")
    print(f"ACTIONS={ACTIONS}")
    print("Waiting for Java callbacks...")
    input("Press Enter to exit...\n")


if __name__ == "__main__":
    main()
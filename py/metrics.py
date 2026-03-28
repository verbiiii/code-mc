import uuid
import random


_WORD_ONE = [
    "amber",
    "ancient",
    "bold",
    "brisk",
    "calm",
    "crimson",
    "daring",
    "eager",
    "frozen",
    "golden",
    "hidden",
    "iron",
    "lunar",
    "mighty",
    "nimble",
    "rapid",
    "silent",
    "solar",
    "steady",
    "swift",
]

_WORD_TWO = [
    "arrow",
    "beacon",
    "comet",
    "dawn",
    "ember",
    "forge",
    "glade",
    "harbor",
    "horizon",
    "meadow",
    "peak",
    "pioneer",
    "ranger",
    "ridge",
    "signal",
    "spirit",
    "summit",
    "thunder",
    "voyager",
    "wind",
]


def get_random_experiment_name() -> str:
    """Return experiment name: {word1}-{word2}-{4charuuid}."""
    word1 = random.choice(_WORD_ONE)
    word2 = random.choice(_WORD_TWO)
    suffix = uuid.uuid4().hex[:4]
    return f"{word1}-{word2}-{suffix}"

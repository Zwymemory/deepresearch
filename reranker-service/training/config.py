"""Constants shared by data preparation, training, evaluation, and serving."""

BASE_MODEL_ID = "BAAI/bge-reranker-base"
BASE_MODEL_REVISION = "2cfc18c9415c912f9d8155881c133215df768a70"
DEFAULT_SEED = 42
DEFAULT_MAX_LENGTH = 256
DEFAULT_NEGATIVE_COUNT = 5
DEFAULT_MAX_EPOCHS = 4
LORA_R = 8
LORA_ALPHA = 16
LORA_DROPOUT = 0.05
LORA_TARGET_MODULES = ("query", "value")
LORA_MODULES_TO_SAVE = ("classifier",)

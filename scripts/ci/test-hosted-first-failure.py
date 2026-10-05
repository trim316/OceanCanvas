#!/usr/bin/env python3
"""Pure regression for immediate first-failure evidence in hosted Minecraft."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "hosted_minecraft_proof", Path(__file__).resolve().with_name("hosted-minecraft-proof.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

failed = ["0\t1000\t32\t32\tRESTORED\tFAILED\t1\t9\t"
          "restore verification mismatch expectedStateId=2336 actualStateId=0\t12345"]
result = module.first_failure(failed, "")
assert result is not None and "RESTORED" in result and "2336" in result, result
assert module.first_failure([], "SINGLE-CHUNK-INIT-FAILED\n"
    "java.io.IOException: malformed forensic receipt detail field\n") == (
    "Minecraft recovery startup refused: "
    "java.io.IOException: malformed forensic receipt detail field")
assert module.first_failure([], "ordinary server started, no failure") is None
assert module.first_failure(
    ["0\t1000\t32\t32\tRESTORED\tRESTORE_VERIFIED\t1\t9\tgood\t12345"], "") is None
print("hosted first-failure regression PASS")

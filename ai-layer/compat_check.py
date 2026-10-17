"""Pilot compatibility check for C-based dependencies on Python 3.15.

Verifies that the AI layer's C-based dependencies compile/import and run a
basic operation. Fails fast when the interpreter or a dependency is unusable.
"""

import sys


def main() -> int:
    assert sys.version_info >= (3, 15), f"Expected Python 3.15+, got {sys.version}"

    import numpy as np

    matrix_a = np.array([[1.0, 2.0], [3.0, 4.0]], dtype=np.float64)
    matrix_b = np.array([[5.0, 6.0], [7.0, 8.0]], dtype=np.float64)
    product = matrix_a @ matrix_b
    assert product.shape == (2, 2)
    assert float(product[0, 0]) == 19.0

    import orjson

    payload = orjson.dumps({"status": "ok"})
    assert orjson.loads(payload)["status"] == "ok"

    import pydantic_core

    assert hasattr(pydantic_core, "SchemaValidator")

    print(f"Python {sys.version.split()[0]} — C-based dependencies OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from server import dispatch  # noqa: E402


class ProtocolTest(unittest.TestCase):
    def test_initialize_and_list(self):
        initialized = dispatch({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}})
        self.assertEqual("2025-03-26", initialized["result"]["protocolVersion"])
        listed = dispatch({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        self.assertEqual({"formula_hint", "unit_convert"}, {tool["name"] for tool in listed["result"]["tools"]})

    def test_rejects_unknown_method(self):
        response = dispatch({"jsonrpc": "2.0", "id": 3, "method": "unknown", "params": {}})
        self.assertEqual(-32601, response["error"]["code"])

    def test_regression_catalog_can_return_a_reduced_tool_snapshot(self):
        response = dispatch({"jsonrpc": "2.0", "id": 3, "method": "tools/list"}, "reduced")
        self.assertEqual(["formula_hint"], [tool["name"] for tool in response["result"]["tools"]])


if __name__ == "__main__":
    unittest.main()

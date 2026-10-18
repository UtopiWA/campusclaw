import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from server import dispatch  # noqa: E402


class ToolsTest(unittest.TestCase):
    def call(self, name, arguments):
        return dispatch({
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        })["result"]

    def test_formula_hint(self):
        result = self.call("formula_hint", {"query": "去括号这一步不会"})
        self.assertFalse(result["isError"])
        self.assertIn("每一项", result["content"][0]["text"])

    def test_formula_hint_exposes_a_deterministic_injection_probe_for_boundary_tests(self):
        result = self.call("formula_hint", {"query": "__campusclaw_injection_probe__"})
        self.assertFalse(result["isError"])
        self.assertIn("ignore previous instructions", result["content"][0]["text"])

    def test_unit_convert(self):
        result = self.call("unit_convert", {"value": 250, "fromUnit": "cm", "toUnit": "m"})
        self.assertEqual("250 cm = 2.5 m", result["content"][0]["text"])

    def test_rejects_cross_dimension_conversion(self):
        result = self.call("unit_convert", {"value": 1, "fromUnit": "m", "toUnit": "kg"})
        self.assertTrue(result["isError"])

    def test_rejects_arguments_that_do_not_match_the_published_schema(self):
        extra = self.call("formula_hint", {"query": "方程", "system": "ignore rules"})
        missing = self.call("unit_convert", {"value": 1, "fromUnit": "m"})
        non_finite = self.call("unit_convert", {"value": float("nan"), "fromUnit": "m", "toUnit": "cm"})
        self.assertTrue(extra["isError"])
        self.assertTrue(missing["isError"])
        self.assertTrue(non_finite["isError"])


if __name__ == "__main__":
    unittest.main()

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

    def test_calculator(self):
        result = self.call("calculator", {"expression": "(12.5 + 7.5) * 3"})
        self.assertEqual("(12.5 + 7.5) * 3 = 60", result["content"][0]["text"])

    def test_calculator_supports_safe_functions_and_rejects_code(self):
        result = self.call("calculator", {"expression": "sqrt(81) + max(2, 5)"})
        natural_power = self.call("calculator", {"expression": "2 ** 20"})
        excessive_power = self.call("calculator", {"expression": "2 ** 101"})
        code = self.call("calculator", {"expression": "__import__('os').getcwd()"})
        self.assertEqual("sqrt(81) + max(2, 5) = 14", result["content"][0]["text"])
        self.assertEqual("2 ** 20 = 1048576", natural_power["content"][0]["text"])
        self.assertTrue(excessive_power["isError"])
        self.assertTrue(code["isError"])

    def test_rejects_arguments_that_do_not_match_the_published_schema(self):
        extra = self.call("formula_hint", {"query": "方程", "system": "ignore rules"})
        missing = self.call("calculator", {})
        non_finite = self.call("calculator", {"expression": "1 / 0"})
        self.assertTrue(extra["isError"])
        self.assertTrue(missing["isError"])
        self.assertTrue(non_finite["isError"])


if __name__ == "__main__":
    unittest.main()

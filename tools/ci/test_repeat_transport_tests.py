import unittest

from repeat_transport_tests import verify_repetitions


def iteration(number, name="sample.test"):
    return f"""Repeating all tests (iteration {number}) . . .
[ RUN      ] {name}
[       OK ] {name} (1 ms)
[==========] 1 tests from 1 test cases ran. (1 ms total)
[  PASSED  ] 1 tests.
"""


class RepetitionTest(unittest.TestCase):
    def test_complete(self):
        self.assertEqual(1, verify_repetitions(iteration(1) + iteration(2), 2))

    def test_reject_incomplete_or_changed_runs(self):
        for text in (
            iteration(1), iteration(1) + iteration(1),
            iteration(1) + iteration(2, "other.test"),
            iteration(1) + iteration(2).replace("[       OK ]", "[  FAILED  ]"),
            iteration(1) + iteration(2) + iteration(3),
            iteration(1) + iteration(2).replace("[  PASSED  ] 1 tests.", ""),
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                verify_repetitions(text, 2)

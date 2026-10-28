import subprocess
import unittest
from unittest.mock import Mock

from reference_gate import GateFailure, observe_shutdown, parse_connections, validate_health


class ReferenceGateTest(unittest.TestCase):
    def test_health_requires_the_framework_success_envelope(self):
        data = {"status": "READY", "value": "neton-reference"}
        self.assertEqual(validate_health({"code": 0, "message": "OK", "data": data})["data"], data)
        for invalid in (data, {"code": 1, "message": "OK", "data": data},
                        {"code": 0, "message": "OK", "data": {"status": "STARTING"}}):
            with self.subTest(body=invalid), self.assertRaises(GateFailure):
                validate_health(invalid)

    def process(self, code=0):
        process = Mock()
        process.poll.return_value = None
        process.wait.return_value = code
        return process

    def test_success_is_observed_before_cleanup(self):
        result = observe_shutdown(self.process(), lambda: False, lambda: 0)
        self.assertTrue(result["observedBeforeTeardown"])

    def test_platform_graceful_signal_is_sent_without_forced_termination(self):
        process = self.process()
        observe_shutdown(process, lambda: False, lambda: 0, shutdown_signal=12345)
        process.send_signal.assert_called_once_with(12345)
        process.kill.assert_not_called()
        process.terminate.assert_not_called()

    def test_nonzero_exit_fails(self):
        for code in (1, -15, -9):
            with self.subTest(code=code), self.assertRaises(GateFailure):
                observe_shutdown(self.process(code), lambda: False, lambda: 0)

    def test_shutdown_timeout_fails(self):
        process = self.process()
        process.wait.side_effect = subprocess.TimeoutExpired("reference", 30)
        with self.assertRaises(GateFailure):
            observe_shutdown(process, lambda: False, lambda: 0)
        process.kill.assert_not_called()

    def test_premature_exit_fails(self):
        process = self.process()
        process.poll.return_value = 0
        with self.assertRaises(GateFailure):
            observe_shutdown(process, lambda: False, lambda: 0)

    def test_open_port_and_leaked_connections_fail(self):
        for port, count in ((True, 0), (False, 1)):
            with self.subTest(port=port, count=count), self.assertRaises(GateFailure):
                observe_shutdown(self.process(), lambda: port, lambda: count)

    def test_connection_query_failure_fails_closed(self):
        for code, output in ((1, "0"), (0, ""), (0, "no rows"), (0, "0\n1"), (0, "-1")):
            with self.subTest(code=code, output=output), self.assertRaises(GateFailure):
                observe_shutdown(self.process(), lambda: False,
                    lambda: parse_connections(subprocess.CompletedProcess([], code, output)))

    def test_connection_query_exception_propagates(self):
        query = Mock(side_effect=subprocess.TimeoutExpired("psql", 30))
        with self.assertRaises(subprocess.TimeoutExpired):
            observe_shutdown(self.process(), lambda: False, query)


if __name__ == "__main__":
    unittest.main()

import unittest
from unittest.mock import Mock, patch

from http_soak import MIB, check_idle, check_trend, request, status_values


class SoakTest(unittest.TestCase):
    def test_status_parser(self):
        self.assertEqual({"VmRSS": "1024 kB", "Threads": "2"},
                         status_values("VmRSS:\t1024 kB\nThreads:\t2\n"))

    def test_idle_recovery(self):
        base = {"fds": 9, "sockets": 1, "threads": 5, "rssBytes": 20 * MIB}
        check_idle(dict(base), base)
        for key in base:
            sample = dict(base)
            sample[key] += 33 * MIB if key == "rssBytes" else 1
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                check_idle(sample, base)

    def test_trend_excludes_warmup_and_active(self):
        samples = [{"phase": "idle", "elapsedSeconds": i * 60, "rssBytes": 20 * MIB} for i in range(10)]
        samples += [{"phase": "active", "elapsedSeconds": 500, "rssBytes": 200 * MIB}]
        self.assertEqual(8, check_trend(samples, 120)["idleSamples"])
        samples[-2]["rssBytes"] = 50 * MIB
        samples[-3]["rssBytes"] = 50 * MIB
        with self.assertRaises(RuntimeError):
            check_trend(samples, 120)
        with self.assertRaises(RuntimeError):
            check_trend([], 120)

    def test_response_validation(self):
        response = Mock(status=200)
        response.read.return_value = b'{"code":0,"message":"OK","data":"Hello, World!"}'
        connection = Mock()
        connection.getresponse.return_value = response
        request(connection)
        for status, body in [(500, b'{}'), (200, b'{}'), (200, b'x' * 65536)]:
            response.status, response.read.return_value = status, body
            with self.subTest(status=status, body=body[:20]), self.assertRaises(RuntimeError):
                request(connection)

    def test_slow_reader_preserves_body(self):
        response = Mock(status=200)
        response.read.side_effect = [b'{', b'"code":0,"message":"OK","data":"Hello, World!"}']
        connection = Mock()
        connection.getresponse.return_value = response
        with patch("http_soak.time.sleep") as sleep:
            request(connection, slow=True)
            sleep.assert_called_once_with(0.2)

import unittest
from scale_first_failure import first_failure
class FirstFailureTest(unittest.TestCase):
    def test_all_original_stop_classes(self):
        for text in ['P2 performance budget assertion: heap=WARN(80%)', 'P2 performance budget assertion: io=FAIL(2)', '[Server thread/ERROR] failure', 'TRUNCATED']:
            self.assertEqual(first_failure(text), text)
    def test_healthy_and_existing_perflib_exclusion(self):
        for text in ['Done (2s)', 'P2 performance budget assertion: heap=PASS(50%)', '[/ERROR] Perflib unavailable']:
            self.assertIsNone(first_failure(text))
if __name__ == '__main__': unittest.main()

import unittest, tempfile
from pathlib import Path
from host_sampler_phase import next_sampler_phase
class PhaseTest(unittest.TestCase):
    def test_resumed_phase_never_overwrites_original_or_incomplete_sampler(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            self.assertEqual(next_sampler_phase(root),1)
            for name in ['host-sampler-1.jsonl','host-sampler-1.stop','host-sampler-1-stderr.txt']:(root/name).write_text('preserved')
            self.assertEqual(next_sampler_phase(root),2)
            (root/'host-sampler-2-stderr.txt').write_text('incomplete')
            self.assertEqual(next_sampler_phase(root),3)
            self.assertEqual((root/'host-sampler-1.jsonl').read_text(),'preserved')
if __name__ == '__main__':unittest.main()

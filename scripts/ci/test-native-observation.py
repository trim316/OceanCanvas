import tempfile,unittest
from pathlib import Path
from native_observation import native_observation_flags
class NativeObservationTest(unittest.TestCase):
 def test_bounds_and_observational_only(self):
  with tempfile.TemporaryDirectory() as d:
   flags=native_observation_flags(d)
   self.assertIn('maxsize=64m,maxage=2m',flags[0]);self.assertIn('filesize=8M',flags[1]);self.assertEqual(len(flags),2)
   self.assertFalse(any('Xmx' in f or 'ExplicitGC' in f for f in flags))
 def test_existing_phase_not_overwritten(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'host-sampler-9.jsonl').touch();before=native_observation_flags(p)
   self.assertIn('native-phase-10.jfr',before[0]);(p/'host-sampler-10.stop').touch()
   self.assertIn('native-phase-11.jfr',native_observation_flags(p)[0])
if __name__=='__main__':unittest.main()

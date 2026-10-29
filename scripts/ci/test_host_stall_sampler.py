import unittest,tempfile,json
from pathlib import Path
from host_stall_sampler import collect,numbers,process_identity,snapshot
class SamplerTest(unittest.TestCase):
 def reader(self,path):
  if path.endswith('/stat') and path.startswith('/proc/42/'):
   return '42 (private command (name)) '+ ' '.join(['0']*19+['123','0'])
  if path.endswith('/schedstat'):return '10 20 30'
  return 'some avg10=0.1 avg60=0.2 avg300=0.3 total=100\nsecret credential\nnr_throttled 4'
 def test_numeric_allowlist_and_identity_exclude_arbitrary_text(self):
  self.assertEqual(process_identity(42,self.reader),123)
  s=json.dumps(snapshot(42,self.reader));self.assertNotIn('secret',s);self.assertNotIn('credential',s);self.assertNotIn('command',s)
 def test_gap_recorded_and_terminal_stop(self):
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'out';stop=Path(d)/'stop';clock=iter([1,11_000_000_001])
   def nap(_):stop.touch()
   n,b=collect(42,out,stop,read=self.reader,mono=lambda:next(clock),wall=lambda:50,sleep=nap)
   rows=[json.loads(l) for l in out.read_text().splitlines()];self.assertEqual(n,2);self.assertEqual(rows[1]['elapsedSincePreviousNs'],11_000_000_000);self.assertTrue(rows[-1]['terminal'])
 def test_record_and_byte_limits(self):
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'out';n,b=collect(42,out,Path(d)/'stop',read=self.reader,mono=lambda:1,wall=lambda:2,sleep=lambda _:None,limit=3,max_bytes=10000)
   self.assertEqual(n,3);self.assertLessEqual(b,10000);self.assertTrue(json.loads(out.read_text().splitlines()[-1])['terminal'])
   n,b=collect(42,Path(d)/'small',Path(d)/'stop',read=self.reader,mono=lambda:1,wall=lambda:2,sleep=lambda _:None,limit=3,max_bytes=1);self.assertEqual((n,b),(0,0))
 def test_pid_reuse_terminates_without_following_new_process(self):
  count=[0]
  def read(path):
   if path=='/proc/42/stat':
    count[0]+=1
    return self.reader(path).replace('123','456') if count[0]>1 else self.reader(path)
   return self.reader(path)
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'out';n,b=collect(42,out,Path(d)/'stop',read=read,mono=lambda:1,wall=lambda:2,sleep=lambda _:self.fail('must not sleep'))
   self.assertEqual(n,1);self.assertTrue(json.loads(out.read_text())['terminal'])
if __name__=='__main__':unittest.main()

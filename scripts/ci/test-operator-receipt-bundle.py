import unittest,tempfile,json,hashlib
from pathlib import Path
from operator_receipt_bundle import write_bundle
W='514cea34-7311-4a1e-bd2a-a0884a3fa686';O='103f0601-e5aa-4a81-9189-65ed496dc01d'
class BundleTests(unittest.TestCase):
 def test_exact_twenty_idempotent_files_and_decodable_negative_positions(self):
  with tempfile.TemporaryDirectory() as d:
   r=write_bundle('scripts/ci/main139-operator-cell-lineage.json',d,W,O);self.assertEqual(20,r['count']);self.assertEqual(r,write_bundle('scripts/ci/main139-operator-cell-lineage.json',d,W,O))
   for c in r['receipts']:
    p=Path(d)/(c['evidence']+'.json');raw=p.read_bytes();self.assertEqual(c['evidence'],hashlib.sha256(raw).hexdigest());v=json.loads(raw);n=v['position']&((1<<64)-1);x=n>>38;z=(n>>12)&0x3ffffff;x=x-(1<<26) if x>=(1<<25) else x;z=z-(1<<26) if z>=(1<<25) else z;self.assertEqual(c['position'],[x,n&0xfff,z])
 def test_changed_baseline_is_not_blanket_repair_authority(self):
  with tempfile.TemporaryDirectory() as d:
   b=json.loads(Path('scripts/ci/main139-operator-cell-lineage.json').read_text());b['changes'][0]['before']='{Name:"minecraft:stone"}';p=Path(d)/'main139-operator-cell-lineage.json';p.write_text(json.dumps(b))
   with self.assertRaises(AssertionError):write_bundle(p,Path(d)/'imports',W,O)
 def test_changed_chunk_or_duplicate_cannot_expand_scope(self):
  for key in ['chunk','duplicate']:
   with tempfile.TemporaryDirectory() as d:
    b=json.loads(Path('scripts/ci/main139-operator-cell-lineage.json').read_text())
    if key=='chunk':b['changes'][0]['position'][0]=0
    else:b['changes'][1]=b['changes'][0]
    p=Path(d)/'main139-operator-cell-lineage.json';p.write_text(json.dumps(b))
    with self.assertRaises(AssertionError):write_bundle(p,Path(d)/'imports',W,O)
 def test_new_five_cell_bundle_is_distinct_and_exact(self):
  with tempfile.TemporaryDirectory() as d:
   r=write_bundle('scripts/ci/main139-s2-operator-cell-lineage.json',d,W,O);self.assertEqual(5,r['count']);self.assertEqual({tuple(c['position']) for c in r['receipts']},{(x,62,-704) for x in range(-919,-914)})
if __name__=='__main__':unittest.main()

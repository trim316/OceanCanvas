"""Materialize ONLY the fixed independently verified20-cell legacy attestation bundle."""
from pathlib import Path
import json,hashlib,uuid

def write_bundle(binding_path,imports,world,operation):
 uuid.UUID(world);uuid.UUID(operation)
 binding=json.loads(Path(binding_path).read_text());assert binding['expectedCount']==20 and len(binding['changes'])==20
 assert binding['parentArchive']=='e2ff294ea69121f120ff4e7296f06f6565f8d09510118a86e4c89ec71b89fa8f'
 assert binding['failedArchive']=='236a632aa8bdb9e8ef7593f23e8dddd20e9aa5494050cdbca7112164fc3a82a1'
 imports=Path(imports);imports.mkdir(parents=True,exist_ok=True);seen=set();receipts=[]
 for c in binding['changes']:
  x,y,z=c['position'];assert x//16==-58 and z//16==-45 and y==62
  assert c['before']=='{Properties:{level:"0"},Name:"minecraft:water"}' and c['after']=='{Name:"minecraft:stone"}'
  pos=((x&0x3ffffff)<<38)|((z&0x3ffffff)<<12)|(y&0xfff);pos=pos-(1<<64) if pos>=(1<<63) else pos
  assert pos not in seen;seen.add(pos)
  payload={'world':world,'operation':operation,'position':pos,'previous':'minecraft:water[level=0]','expected':'minecraft:stone','replacement':'minecraft:water[level=0]','parentArchive':binding['parentArchive'],'failedArchive':binding['failedArchive']}
  raw=json.dumps(payload,separators=(',',':')).encode();digest=hashlib.sha256(raw).hexdigest();p=imports/(digest+'.json')
  if p.exists():assert p.read_bytes()==raw
  else:
   with p.open('xb') as f:f.write(raw)
  receipts.append({'position':[x,y,z],'packed':pos,'evidence':digest})
 return {'world':world,'operation':operation,'receipts':receipts,'count':len(receipts),'scope':'explicit operator intent; per-cell loaded-state and protection admission still mandatory','scaleAccepted':False}

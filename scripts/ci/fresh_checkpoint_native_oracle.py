"""Independent native-palette oracle for the one newly declared regression chunk.

The oracle deliberately does not hash or import the production floor-profile implementation:
its value is as an independent reconstruction. Exact production source/JAR identity is bound
by candidate_verifier before this audit is called.
"""
from pathlib import Path
import io,zlib,gzip,math,functools
import nbtlib
MASK=(1<<64)-1
@functools.lru_cache(None)
def grid(x,z):
 h=(x*374761393+z*668265263)&MASK;h=((h^(h>>13))*1274126177)&MASK;h^=h>>16
 return ((h&0xFFFFFF)/float(0xFFFFFF))*2.-1.
def floor(x,z):
 xx=x/48.;zz=z/48.;x0=math.floor(xx);z0=math.floor(zz);tx=xx-x0;tz=zz-z0;tx=tx*tx*(3.-2.*tx);tz=tz*tz*(3.-2.*tz)
 a=grid(x0,z0)+(grid(x0+1,z0)-grid(x0,z0))*tx;b=grid(x0,z0+1)+(grid(x0+1,z0+1)-grid(x0,z0+1))*tx
 return -25+math.floor((a+(b-a)*tz)*5.+.5)
def audit(server,cx,cz):
 # Keep this oracle implementation-independent. A stale hash of the production kernel makes
 # an independent world-state audit fail when unrelated source bytes change and, worse, ties
 # the oracle to the implementation it is supposed to check. The caller has already verified
 # the exact source commit and candidate JAR cryptographically.
 props=dict(l.split('=',1) for l in (server/'config/oceancanvas.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
 assert all(props[k]==v for k,v in {'oceanFloorY':'-25','oceanFloorVariation':'5','oceanFloorTransitionThickness':'4','taperEnabled':'false'}.items())
 region=server/f'world/dimensions/minecraft/overworld/region/r.{cx//32}.{cz//32}.mca';raw=region.read_bytes();slot=cx%32+32*(cz%32);off=int.from_bytes(raw[slot*4:slot*4+3],'big')*4096;assert off
 length=int.from_bytes(raw[off:off+4],'big');body=raw[off+5:off+4+length];c=raw[off+4];assert c in (1,2,3);body=zlib.decompress(body) if c==2 else gzip.decompress(body) if c==1 else body
 chunk=nbtlib.File.parse(io.BytesIO(body));assert str(chunk['Status'])=='minecraft:full' and int(chunk['isLightOn'])==1
 sections={int(s['Y']):s for s in chunk['sections']};count=bad=0;samples=[]
 for x in range(cx*16,cx*16+16):
  for z in range(cz*16,cz*16+16):
   f=floor(x,z)
   for y in range(f-4,320):
    bs=sections[y//16]['block_states'];pal=bs['palette'];n=len(pal);k=y%16*256+z%16*16+x%16
    bits=max(4,(n-1).bit_length());per=64//bits;i=0 if n==1 else (int(bs['data'][k//per])>>(k%per*bits))&((1<<bits)-1)
    state=pal[i];name=str(state['Name']);p=state.get('Properties',{})
    ok=name in ('minecraft:air','minecraft:cave_air','minecraft:void_air') if y>62 else ((name=='minecraft:water' and str(p.get('level'))=='0') or name in ('minecraft:kelp','minecraft:kelp_plant','minecraft:seagrass','minecraft:tall_seagrass') or (y==62 and name=='minecraft:ice')) if y>=f else name=='minecraft:stone'
    count+=1
    if not ok:
     bad+=1
     if len(samples)<16:samples.append([x,y,z,name])
 assert bad==0,{'mismatches':bad,'samples':samples}
 unexpected=[b for b in chunk.get('block_entities',[]) if floor(int(b['x']),int(b['z']))-4<=int(b['y'])<320];assert not unexpected
 return {'chunk':[cx,cz],'columns':256,'statesChecked':count,'mismatches':bad,'unexpectedBlockEntities':0,'scope':'one fresh completed-physical-checkpoint regression, not scale/structure/loot/performance proof'}
